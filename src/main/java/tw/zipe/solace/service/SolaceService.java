package tw.zipe.solace.service;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.Queue;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.Topic;
import com.solacesystems.jcsmp.XMLMessageConsumer;
import com.solacesystems.jcsmp.XMLMessageListener;
import com.solacesystems.jcsmp.XMLMessageProducer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Solace 核心服務類別
 * <p>
 * 封裝了與 Solace 訊息系統互動的所有底層邏輯，包括連線管理、訊息的發送與接收、檔案傳輸以及 Topic/Queue 的訂閱。
 * 這個類別使用 Spring 的 @Service 註解，由 Spring 容器進行管理。
 * </p>
 */
@Service
public class SolaceService {

    private static final Logger logger = LoggerFactory.getLogger(SolaceService.class);

    // 從 application.properties 中注入 Solace 連線資訊
    @Value("${solace.jms.host}")
    private String host;

    @Value("${solace.jms.msg-vpn}")
    private String vpnName;

    @Value("${solace.jms.client-username}")
    private String username;

    @Value("${solace.jms.client-password}")
    private String password;

    @Value("${solace.received.files.directory:received_files}")
    private String receivedFilesDirectory;

    @Value("${solace.jms.ssl.trust-store:#{null}}")
    private String trustStore;

    @Value("${solace.jms.ssl.trust-store-password:#{null}}")
    private String trustStorePassword;

    @Value("${solace.jms.ssl.validate-certificate:true}")
    private boolean validateCertificate;

    @Value("${solace.default.topic:default/topic}")
    private String defaultTopic;

    @Value("${solace.default.queue:default-queue}")
    private String defaultQueue;

    private JCSMPSession session;
    private XMLMessageProducer producer;

    // 追蹤消費者和 Flow 以便優雅地關閉
    private final List<XMLMessageConsumer> consumers = new ArrayList<>();
    private final List<FlowReceiver> flowReceivers = new ArrayList<>();

    // 用於緩存從 Topic 和 Queue 收到的文字訊息
    // Key: Topic/Queue 名稱, Value: 訊息列表
    // 使用 ConcurrentHashMap 和 ConcurrentLinkedQueue 以確保線程安全
    private final Map<String, ConcurrentLinkedQueue<String>> topicMessagesCache = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> queueMessagesCache = new ConcurrentHashMap<>();

    /**
     * 初始化 Solace 連線。
     * <p>
     * 使用 @PostConstruct 註解，在 Spring Bean 初始化後自動執行。
     * 負責建立並連接到 Solace Session，並初始化訊息生產者。
     * </p>
     * @throws JCSMPException 如果連線或初始化過程中發生錯誤。
     */
    @PostConstruct
    public void initialize() throws JCSMPException {
        logger.info("正在初始化 Solace 連線...");

        // --- Host 協定轉換 (smf -> tcp) ---
        // 為了與 JCSMP API 相容，自動將 'smf://' 和 'smfs://' 協定轉換為 'tcp://' 和 'tcps://'。
        // 這樣可以增加設定檔的靈活性。
        String connectionHost = this.host;
        if (connectionHost != null && (connectionHost.contains("smf://") || connectionHost.contains("smfs://"))) {
            logger.info("偵測到 'smf(s)://' 協定，將自動轉換為 'tcp(s)://'。原始 host: {}", this.host);
            connectionHost = connectionHost.replace("smf://", "tcp://").replace("smfs://", "tcps://");
            logger.info("轉換後 host: {}", connectionHost);
        }

        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, connectionHost);
        properties.setProperty(JCSMPProperties.USERNAME, username);
        properties.setProperty(JCSMPProperties.PASSWORD, password);
        properties.setProperty(JCSMPProperties.VPN_NAME, vpnName);

        // --- SSL/TLS Configuration ---
        // 檢查 host 字串中是否包含 'tcps://' 來決定是否啟用 SSL。
        // 使用 contains() 而非 startsWith() 是為了正確處理高可用性 (HA) 的主機列表 (例如 "tcp://host1,tcps://host2")。
        String lowerCaseHost = (connectionHost != null) ? connectionHost.toLowerCase() : "";
        if (lowerCaseHost.contains("tcps://")) {
            logger.info("偵測到安全連線 (tcps://)，正在設定 SSL 屬性...");
            // 設定自訂的 TrustStore，如果 application.properties 中有提供
            if (trustStore != null && !trustStore.isEmpty()) {
                properties.setProperty(JCSMPProperties.SSL_TRUST_STORE, trustStore);
                if (trustStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_TRUST_STORE_PASSWORD, trustStorePassword);
                }
                logger.info("已設定自訂 TrustStore: {}", trustStore);
            }

            // 允許關閉憑證驗證 (僅建議用於開發/測試環境)
            properties.setBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, validateCertificate);
            if (!validateCertificate) {
                logger.warn("!!! 安全性警告: Solace 連線已停用憑證驗證 (SSL_VALIDATE_CERTIFICATE=false)。請勿在生產環境中使用此設定。");
            }
        }
        session = JCSMPFactory.onlyInstance().createSession(properties);
        session.connect();
        producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
            public void responseReceived(String messageID) {
                logger.info("生產者已收到訊息確認回覆，ID: " + messageID);
            }
            public void handleError(String messageID, JCSMPException e, long timestamp) {
                logger.error("生產者收到錯誤回覆，ID: {} @ {} - {}", messageID, timestamp, e);
            }
        });
        logger.info("Solace 連線已成功建立。");
    }

    /**
     * 關閉 Solace 連線。
     * <p>
     * 使用 @PreDestroy 註解，在 Spring Bean 銷毀前自動執行。
     * 確保應用程式關閉時，能優雅地關閉 Solace Session。
     * </p>
     */
    @PreDestroy
    public void close() {
        logger.info("正在關閉 Solace 資源...");

        // 停止所有活動的 Flow 接收器
        for (FlowReceiver flow : flowReceivers) {
            flow.stop();
        }

        // 關閉所有活動的訊息消費者
        for (XMLMessageConsumer consumer : consumers) {
            consumer.close();
        }

        if (session != null && !session.isClosed()) {
            session.closeSession();
            logger.info("Solace 連線已關閉。");
        }
    }

    /**
     * 發送文字訊息到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地的名稱。
     * @param type            目的地的類型 (TOPIC 或 QUEUE)。
     * @param message         要發送的文字訊息。
     * @throws JCSMPException 如果發送過程中發生錯誤。
     */
    public void sendTextMessage(String destinationName, DestinationType type, String message) throws JCSMPException {
        Destination destination = type == DestinationType.TOPIC ?
                JCSMPFactory.onlyInstance().createTopic(destinationName) :
                JCSMPFactory.onlyInstance().createQueue(destinationName);

        TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
        msg.setText(message);
        producer.send(msg, destination);
        logger.info("已發送訊息到 {} '{}': {}", type, destinationName, message);
    }

    /**
     * 將檔案作為訊息發送到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地的名稱。
     * @param type            目的地的類型 (TOPIC 或 QUEUE)。
     * @param fileName        檔案的原始名稱。
     * @param fileData        檔案的位元組陣列。
     * @throws JCSMPException 如果發送過程中發生錯誤。
     */
    public void sendFile(String destinationName, DestinationType type, String fileName, byte[] fileData) throws JCSMPException {
        Destination destination = type == DestinationType.TOPIC ?
                JCSMPFactory.onlyInstance().createTopic(destinationName) :
                JCSMPFactory.onlyInstance().createQueue(destinationName);

        BytesXMLMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
        msg.writeAttachment(fileData);

        SDTMap properties = JCSMPFactory.onlyInstance().createMap();
        properties.putString("FILE_NAME", fileName);
        properties.putLong("FILE_SIZE", (long) fileData.length);
        msg.setProperties(properties);

        producer.send(msg, destination);
        logger.info("已發送檔案 '{}' 到 {} '{}'", fileName, type, destinationName);
    }

    /**
     * 訂閱指定的 Topic 以接收訊息。
     *
     * @param topicName 要訂閱的 Topic 名稱。
     * @throws JCSMPException 如果訂閱過程中發生錯誤。
     */
    public void subscribeToTopic(String topicName) throws JCSMPException {
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
        XMLMessageConsumer consumer = session.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                handleReceivedMessage(msg, topicName, DestinationType.TOPIC);
            }

            @Override
            public void onException(JCSMPException e) {
                logger.error("消費者在 Topic {} 上發生異常: {}", topicName, e.getMessage(), e);
            }
        });
        session.addSubscription(topic);
        consumer.start();
        consumers.add(consumer); // 追蹤消費者
        logger.info("已成功訂閱 Topic: {}", topicName);
    }

    /**
     * 從指定的 Queue 開始接收訊息。
     *
     * @param queueName 要監聽的 Queue 名稱。
     * @throws JCSMPException 如果監聽過程中發生錯誤。
     */
    public void receiveFromQueue(String queueName) throws JCSMPException {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

        FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                handleReceivedMessage(msg, queueName, DestinationType.QUEUE);
                msg.ackMessage(); // 確認訊息已處理
            }

            @Override
            public void onException(JCSMPException e) {
                logger.error("Flow 接收器在 Queue {} 上發生異常: {}", queueName, e.getMessage(), e);
            }
        }, flowProps);
        flowReceiver.start();
        flowReceivers.add(flowReceiver); // 追蹤 Flow 接收器
        logger.info("已開始監聽 Queue: {}", queueName);
    }

    /**
     * 統一處理接收到的訊息 (包括文字和檔案)。
     *
     * @param msg    接收到的原始 Solace 訊息。
     * @param source 訊息來源的名稱 (Topic 或 Queue)。
     */
    private void handleReceivedMessage(BytesXMLMessage msg, String source, DestinationType type) {
        // [FIX] 調整判斷順序，優先檢查訊息是否為 TextMessage。
        // 之前的邏輯會先檢查 hasAttachment()，這會導致一個 TextMessage 如果意外地包含了附件（即使是空的），
        // 也會被錯誤地當作檔案訊息處理，從而忽略了其文字內容。
        if (msg instanceof TextMessage) {
            // 處理文字訊息
            String messageText = ((TextMessage) msg).getText();
            logger.info("收到來自 {} '{}' 的文字訊息: {}", type, source, messageText);

            // 根據目的地類型將訊息存入對應的快取
            if (type == DestinationType.TOPIC) {
                topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(messageText);
            } else { // QUEUE
                queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(messageText);
            }
        } else if (msg.hasAttachment()) {
            // 處理檔案訊息
            SDTMap properties = msg.getProperties();
            try {
                // 增加對 properties 的空值檢查，防止因訊息缺少屬性而導致 NullPointerException。
                // 如果收到的訊息帶有附件但沒有 properties 或 FILE_NAME 屬性，將記錄警告並安全地忽略該訊息。
                if (properties == null || !properties.containsKey("FILE_NAME")) {
                    logger.warn("收到的檔案訊息缺少 'FILE_NAME' 屬性，將忽略此訊息。來源: {}", source);
                    return;
                }
                final String fileName = properties.getString("FILE_NAME");
                Path directoryPath = Paths.get(receivedFilesDirectory);
                Files.createDirectories(directoryPath); // 如果目錄不存在則建立

                Path outputPath = directoryPath.resolve(fileName);
                ByteBuffer buffer = msg.getAttachmentByteBuffer();

                // 使用 FileChannel 更安全、高效地寫入檔案
                try (FileChannel fileChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    fileChannel.write(buffer);
                }
                String successMessage = String.format("檔案 '%s' 已成功接收並儲存至: %s", fileName, outputPath.toAbsolutePath());
                logger.info("來自 {} '{}' 的檔案 '{}' 已儲存至 '{}'", type, source, fileName, outputPath.toAbsolutePath());

                // 根據目的地類型將檔案接收成功訊息存入對應的快取
                if (type == DestinationType.TOPIC) {
                    topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(successMessage);
                } else { // QUEUE
                    queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(successMessage);
                }
            } catch (IOException | JCSMPException e) {
                logger.error("處理來自 {} '{}' 的檔案時發生錯誤: {}", type, source, e.getMessage(), e);
            }
        }
    }

    /**
     * 獲取並清除指定 Topic 的快取訊息。
     *
     * @param topicName Topic 的名稱。
     * @return 訊息列表，如果沒有訊息則返回空列表。
     */
    public List<String> getAndClearTopicMessages(String topicName) {
        return getAndClearMessagesFromCache(topicMessagesCache, topicName);
    }

    /**
     * 獲取並清除指定 Queue 的快取訊息。
     *
     * @param queueName Queue 的名稱。
     * @return 訊息列表，如果沒有訊息則返回空列表。
     */
    public List<String> getAndClearQueueMessages(String queueName) {
        return getAndClearMessagesFromCache(queueMessagesCache, queueName);
    }

    /**
     * 從指定的快取中，根據 key (Topic/Queue name) 獲取所有訊息並清除。
     * 這是個輔助方法，以避免程式碼重複。
     *
     * @param cache 快取 Map (topicMessagesCache or queueMessagesCache)
     * @param key   Topic 或 Queue 的名稱
     * @return 訊息列表
     */
    private List<String> getAndClearMessagesFromCache(Map<String, ConcurrentLinkedQueue<String>> cache, String key) {
        // 從快取中找到對應的訊息隊列
        ConcurrentLinkedQueue<String> queue = cache.get(key);

        // 如果隊列不存在 (即從未收到該 Topic/Queue 的訊息)，返回空列表
        if (queue == null) {
            return new ArrayList<>();
        }

        List<String> messages = new ArrayList<>();
        String message;

        // 以線程安全的方式，從隊列中取出所有訊息 (poll 是原子操作)
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }

        return messages;
    }

    /**
     * 定義目的地的類型 (Topic 或 Queue)。
     */
    public enum DestinationType {
        TOPIC, QUEUE
    }

    /**
     * 獲取設定檔中設定的預設 Topic 名稱。
     * @return 預設 Topic 名稱。
     */
    public String getDefaultTopic() {
        return defaultTopic;
    }

    /**
     * 獲取設定檔中設定的預設 Queue 名稱。
     * @return 預設 Queue 名稱。
     */
    public String getDefaultQueue() {
        return defaultQueue;
    }
}
