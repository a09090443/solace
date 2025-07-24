package tw.zipe.solace.service;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.ConsumerFlowProperties;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.FlowReceiver;
import com.solacesystems.jcsmp.JCSMPChannelProperties;
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
import java.util.concurrent.CopyOnWriteArrayList;

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

    /**
     * 從 application.properties 中注入 Solace Broker 的主機位址。
     * 格式通常為 tcp://<host>:<port> 或 tcps://<host>:<port>。
     */
    @Value("${solace.jms.host}")
    private String host;

    /**
     * 從 application.properties 中注入 Solace VPN 的名稱。
     */
    @Value("${solace.jms.msg-vpn}")
    private String vpnName;

    /**
     * 從 application.properties 中注入 Solace 用戶名稱。
     */
    @Value("${solace.jms.client-username}")
    private String username;

    /**
     * 從 application.properties 中注入 Solace 用戶密碼。
     * 預設為空字串。
     */
    @Value("${solace.jms.client-password:}")
    private String password;

    /**
     * 從 application.properties 中注入接收檔案的儲存目錄。
     * 預設為 "received_files"。
     */
    @Value("${solace.received.files.directory:received_files}")
    private String receivedFilesDirectory;

    /**
     * 從 application.properties 中注入 SSL/TLS 連線的信任儲存庫路徑。
     * 預設為 null。
     */
    @Value("${solace.jms.ssl.trust-store:#{null}}")
    private String trustStore;

    /**
     * 從 application.properties 中注入 SSL/TLS 連線的信任儲存庫密碼。
     * 預設為 null。
     */
    @Value("${solace.jms.ssl.trust-store-password:#{null}}")
    private String trustStorePassword;

    /**
     * 從 application.properties 中注入是否驗證 SSL/TLS 憑證。
     * 預設為 true。
     */
    @Value("${solace.jms.ssl.validate-certificate:true}")
    private boolean validateCertificate;

    /**
     * 從 application.properties 中注入預設的 Topic 名稱。
     * 預設為 "default/topic"。
     */
    @Value("${solace.default.topic:default/topic}")
    private String defaultTopic;

    /**
     * 從 application.properties 中注入預設的 Queue 名稱。
     * 預設為 "default-queue"。
     */
    @Value("${solace.default.queue:default-queue}")
    private String defaultQueue;

    /**
     * 從 application.properties 中注入 SSL/TLS 連線的 Key Store 路徑（用於客戶端憑證認證）。
     * 預設為 null。
     */
    @Value("${solace.jms.ssl.key-store:#{null}}")
    private String keyStore;

    /**
     * 從 application.properties 中注入 SSL/TLS 連線的 Key Store 密碼。
     * 預設為 null。
     */
    @Value("${solace.jms.ssl.key-store-password:#{null}}")
    private String keyStorePassword;

    /**
     * 從 application.properties 中注入 SSL/TLS 連線的 Key Store 格式。
     * 預設為 null。
     */
    @Value("${solace.jms.ssl.key-store-format:#{null}}")
    private String keyStoreFormat;

    /**
     * 用於管理生產者 JCSMPSession 的連線池。
     */
    private SolaceConnectionPool producerPool;
    /**
     * 用於管理消費者 JCSMPSession 的連線池。
     */
    private SolaceConnectionPool consumerPool;

    /**
     * 追蹤所有活躍的消費者資訊 (XMLMessageConsumer 或 FlowReceiver)，以便在應用程式關閉時優雅地關閉它們。
     * 使用 CopyOnWriteArrayList 以確保多執行緒環境下的安全。
     */
    private final List<ConsumerInfo> consumerInfos = new CopyOnWriteArrayList<>();

    /**
     * 用於緩存從 Topic 收到的文字訊息，以 Topic 名稱作為鍵。
     */
    private final Map<String, ConcurrentLinkedQueue<String>> topicMessagesCache = new ConcurrentHashMap<>();
    /**
     * 用於緩存從 Queue 收到的文字訊息，以 Queue 名稱作為鍵。
     */
    private final Map<String, ConcurrentLinkedQueue<String>> queueMessagesCache = new ConcurrentHashMap<>();

    /**
     * 服務初始化方法，在 Spring 容器完成屬性注入後自動呼叫。
     * 負責初始化生產者和消費者的 Solace 連線池。
     *
     * @throws JCSMPException 如果 Solace 連線初始化失敗。
     */
    @PostConstruct
    public void initialize() throws JCSMPException {
        logger.info("正在初始化 Solace 連線池和消費者連線...");

        String connectionHost = this.host;
        if (connectionHost != null && (connectionHost.startsWith("smf://") || connectionHost.startsWith("smfs://"))) {
            connectionHost = connectionHost.replace("smf://", "tcp://").replace("smfs://", "tcps://");
        }

        final JCSMPProperties properties = createJCSMPProperties(connectionHost);

        // 初始化生產者連線池
        producerPool = new SolaceConnectionPool(properties);
        logger.info("Solace 生產者連線池已建立。");

        // 初始化消費者連線池
        consumerPool = new SolaceConnectionPool(properties);
        logger.info("Solace 消費者連線池已建立。");
    }

    /**
     * 根據提供的連線主機位址建立 JCSMPProperties 物件。
     * 此方法會設定基本的連線屬性，並根據配置處理 SSL/TLS 和客戶端憑證認證。
     * 同時，它會設定 JCSMP 的自動重連和重新訂閱屬性，以增強連線的韌性。
     *
     * @param connectionHost Solace Broker 的連線主機位址。
     * @return 配置好的 JCSMPProperties 物件。
     */
    private JCSMPProperties createJCSMPProperties(String connectionHost) {
        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, connectionHost);
        properties.setProperty(JCSMPProperties.USERNAME, username);
        properties.setProperty(JCSMPProperties.VPN_NAME, vpnName);

        String lowerCaseHost = (connectionHost != null) ? connectionHost.toLowerCase() : "";
        boolean isSecureConnection = lowerCaseHost.contains("tcps://");
        boolean useClientCertificateAuth = isSecureConnection && keyStore != null && !keyStore.isEmpty();

        if (isSecureConnection) {
            if (trustStore != null && !trustStore.isEmpty()) {
                properties.setProperty(JCSMPProperties.SSL_TRUST_STORE, trustStore);
                if (trustStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_TRUST_STORE_PASSWORD, trustStorePassword);
                }
            }
            if (useClientCertificateAuth) {
                properties.setProperty(JCSMPProperties.SSL_KEY_STORE, keyStore);
                if (keyStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_KEY_STORE_PASSWORD, keyStorePassword);
                }
                if (keyStoreFormat != null) {
                    properties.setProperty(JCSMPProperties.SSL_KEY_STORE_FORMAT, keyStoreFormat);
                }
                properties.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME, JCSMPProperties.AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE);
            } else {
                properties.setProperty(JCSMPProperties.PASSWORD, password);
            }
            properties.setBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, validateCertificate);
        } else {
            properties.setProperty(JCSMPProperties.PASSWORD, password);
        }
        JCSMPChannelProperties cp = (JCSMPChannelProperties) properties
                .getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        // 啟用無限重連
        cp.setReconnectRetries(-1);
        // 重連間隔 3 秒
        cp.setReconnectRetryWaitInMillis(5000);
        // 自動重新訂閱 Topic
        properties.setBooleanProperty(JCSMPProperties.REAPPLY_SUBSCRIPTIONS, true);
        return properties;
    }

    /**
     * 服務銷毀方法，在 Spring 容器關閉時自動呼叫。
     * 負責關閉所有 Solace 連線資源，包括消費者和生產者的連線池。
     */
    @PreDestroy
    public void close() {
        logger.info("正在關閉 Solace 資源...");

        // 關閉消費者資源
        for (ConsumerInfo info : consumerInfos) {
            info.close();
        }
        consumerInfos.clear();

        // 關閉消費者連線池
        if (consumerPool != null) {
            consumerPool.close();
            logger.info("Solace 消費者連線池已關閉。");
        }

        // 關閉生產者連線池
        if (producerPool != null) {
            producerPool.close();
            logger.info("Solace 生產者連線池已關閉。");
        }
    }

    /**
     * 發送文字訊息到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地名稱 (Topic 或 Queue)。
     * @param type            目的地類型 (TOPIC 或 QUEUE)。
     * @param message         要發送的文字訊息內容。
     * @throws Exception 如果發送訊息時發生錯誤。
     */
    public void sendTextMessage(String destinationName, DestinationType type, String message) throws Exception {
        JCSMPSession session = null;
        try {
            session = producerPool.getSession();
            XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                public void responseReceived(String messageID) {
                    logger.info("TEST_CASE_1.1_7.1_PUBLISH_SUCCESS | MessageID: {}", messageID);
                }

                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    logger.error("TEST_CASE_7.2_PUBLISH_FAILURE | MessageID: {}", messageID, e);
                }
            });

            Destination destination = type == DestinationType.TOPIC ?
                    JCSMPFactory.onlyInstance().createTopic(destinationName) :
                    JCSMPFactory.onlyInstance().createQueue(destinationName);

            TextMessage msg = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            msg.setText(message);
            msg.setDMQEligible(true);
            msg.setApplicationMessageId("APP-" + System.currentTimeMillis());
            msg.setApplicationMessageType("TEXT_MESSAGE");
            msg.setSenderTimestamp(System.currentTimeMillis());

            producer.send(msg, destination);
        } finally {
            if (session != null) {
                producerPool.returnSession(session);
            }
        }
    }

    /**
     * 發送檔案到指定的目的地 (Topic 或 Queue)。
     * 檔案內容將作為訊息的附件發送，並包含檔案名稱和大小等屬性。
     *
     * @param destinationName 目的地名稱 (Topic 或 Queue)。
     * @param type            目的地類型 (TOPIC 或 QUEUE)。
     * @param fileName        檔案名稱。
     * @param fileData        檔案的位元組資料。
     * @throws Exception 如果發送檔案時發生錯誤。
     */
    public void sendFile(String destinationName, DestinationType type, String fileName, byte[] fileData) throws Exception {
        JCSMPSession session = null;
        try {
            session = producerPool.getSession();
            XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                public void responseReceived(String messageID) {
                    logger.info("File publish success, MessageID: {}", messageID);
                }

                public void handleError(String messageID, JCSMPException e, long timestamp) {
                    logger.error("File publish failure, MessageID: {}", messageID, e);
                }
            });

            Destination destination = type == DestinationType.TOPIC ?
                    JCSMPFactory.onlyInstance().createTopic(destinationName) :
                    JCSMPFactory.onlyInstance().createQueue(destinationName);

            BytesXMLMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
            msg.writeAttachment(fileData);

            SDTMap properties = JCSMPFactory.onlyInstance().createMap();
            properties.putString("FILE_NAME", fileName);
            properties.putLong("FILE_SIZE", (long) fileData.length);
            msg.setProperties(properties);
            msg.setDMQEligible(true);
            msg.setApplicationMessageId("FILE-" + System.currentTimeMillis());
            msg.setApplicationMessageType("FILE_MESSAGE");
            msg.setSenderTimestamp(System.currentTimeMillis());

            producer.send(msg, destination);
        } finally {
            if (session != null) {
                producerPool.returnSession(session);
            }
        }
    }

    /**
     * 訂閱指定的 Topic，並開始接收來自該 Topic 的訊息。
     * 每個訂閱都會從連線池中獲取一個新的 JCSMPSession。
     *
     * @param topicName 要訂閱的 Topic 名稱。
     * @throws Exception 如果訂閱 Topic 時發生錯誤。
     */
    public void subscribeToTopic(String topicName) throws Exception {
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
        JCSMPSession session = consumerPool.getSession();
        try {
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
            consumerInfos.add(new ConsumerInfo(session, consumer, null));
            logger.info("已成功訂閱 Topic: {}", topicName);
        } catch (Exception e) {
            consumerPool.returnSession(session);
            throw e;
        }
    }

    /**
     * 從指定的 Queue 接收訊息。
     * 每個 Queue 監聽都會從連線池中獲取一個新的 JCSMPSession，並建立一個 FlowReceiver。
     *
     * @param queueName 要監聽的 Queue 名稱。
     * @throws Exception 如果從 Queue 接收訊息時發生錯誤。
     */
    public void receiveFromQueue(String queueName) throws Exception {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        JCSMPSession session = consumerPool.getSession();
        try {
            ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
            flowProps.setEndpoint(queue);
            flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

            FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
                @Override
                public void onReceive(BytesXMLMessage msg) {
                    handleReceivedMessage(msg, queueName, DestinationType.QUEUE);
                    msg.ackMessage();
                }

                @Override
                public void onException(JCSMPException e) {
                    logger.error("Queue {} 消費者發生異常: {}", queueName, e.getMessage(), e);
                }
            }, flowProps);

            flowReceiver.start();
            consumerInfos.add(new ConsumerInfo(session, null, flowReceiver));
            logger.info("已開始監聽 Queue: {}", queueName);
        } catch (Exception e) {
            consumerPool.returnSession(session);
            throw e;
        }
    }

    /**
     * 處理接收到的 Solace 訊息。
     * 根據訊息類型 (文字訊息或檔案訊息) 進行不同的處理，並將訊息內容或檔案儲存路徑緩存起來。
     *
     * @param msg    接收到的 BytesXMLMessage 物件。
     * @param source 訊息來源 (Topic 或 Queue 名稱)。
     * @param type   目的地類型 (TOPIC 或 QUEUE)。
     */
    private void handleReceivedMessage(BytesXMLMessage msg, String source, DestinationType type) {
        if (msg instanceof TextMessage) {
            String messageText = ((TextMessage) msg).getText();
            logger.info("收到來自 {} '{}' 的文字訊息: {}", type, source, messageText);
            ConcurrentLinkedQueue<String> cache = (type == DestinationType.TOPIC) ?
                    topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()) :
                    queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>());
            cache.add(messageText);
        } else if (msg.hasAttachment()) {
            try {
                SDTMap properties = msg.getProperties();
                if (properties == null || !properties.containsKey("FILE_NAME")) {
                    logger.warn("收到的檔案訊息缺少 'FILE_NAME' 屬性，將忽略此訊息。來源: {}", source);
                    return;
                }
                final String fileName = properties.getString("FILE_NAME");
                Path directoryPath = Paths.get(receivedFilesDirectory);
                Files.createDirectories(directoryPath);

                Path outputPath = directoryPath.resolve(fileName);
                ByteBuffer buffer = msg.getAttachmentByteBuffer();

                try (FileChannel fileChannel = FileChannel.open(outputPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    fileChannel.write(buffer);
                }

                String successMessage = String.format("檔案 '%s' 已成功接收並儲存至: %s", fileName, outputPath.toAbsolutePath());
                logger.info(successMessage);
                ConcurrentLinkedQueue<String> cache = (type == DestinationType.TOPIC) ?
                        topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()) :
                        queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>());
                cache.add(successMessage);
            } catch (IOException | JCSMPException e) {
                logger.error("處理來自 {} '{}' 的檔案訊息時發生錯誤", type, source, e);
            }
        }
    }

    /**
     * 獲取並清空指定 Topic 的訊息緩存。
     *
     * @param topicName Topic 名稱。
     * @return 該 Topic 的所有緩存訊息列表。
     */
    public List<String> getAndClearTopicMessages(String topicName) {
        return getAndClearMessagesFromCache(topicMessagesCache, topicName);
    }

    /**
     * 獲取並清空指定 Queue 的訊息緩存。
     *
     * @param queueName Queue 名稱。
     * @return 該 Queue 的所有緩存訊息列表。
     */
    public List<String> getAndClearQueueMessages(String queueName) {
        return getAndClearMessagesFromCache(queueMessagesCache, queueName);
    }

    /**
     * 從指定的訊息緩存中獲取並清空訊息。
     *
     * @param cache 訊息緩存 Map。
     * @param key   緩存的鍵 (Topic 或 Queue 名稱)。
     * @return 緩存中的所有訊息列表。
     */
    private List<String> getAndClearMessagesFromCache(Map<String, ConcurrentLinkedQueue<String>> cache, String key) {
        ConcurrentLinkedQueue<String> queue = cache.get(key);
        if (queue == null) {
            return new ArrayList<>();
        }
        List<String> messages = new ArrayList<>();
        String message;
        while ((message = queue.poll()) != null) {
            messages.add(message);
        }
        return messages;
    }

    /**
     * 定義目的地類型，可以是 Topic 或 Queue。
     */
    public enum DestinationType {
        TOPIC, QUEUE
    }

    /**
     * 獲取預設的 Topic 名稱。
     *
     * @return 預設 Topic 名稱。
     */
    public String getDefaultTopic() {
        return defaultTopic;
    }

    /**
     * 獲取預設的 Queue 名稱。
     *
     * @return 預設 Queue 名稱。
     */
    public String getDefaultQueue() {
        return defaultQueue;
    }

    /**
     * 內部類別，用於封裝消費者相關的資訊 (JCSMPSession, XMLMessageConsumer, FlowReceiver)，
     * 以便在應用程式關閉時統一管理和關閉這些資源。
     */
    private static class ConsumerInfo {
        private final JCSMPSession session;
        private final XMLMessageConsumer consumer;
        private final FlowReceiver flowReceiver;

        /**
         * 構造函數。
         *
         * @param session      關聯的 JCSMPSession。
         * @param consumer     XMLMessageConsumer 實例 (如果適用於 Topic 訂閱)。
         * @param flowReceiver FlowReceiver 實例 (如果適用於 Queue 監聽)。
         */
        public ConsumerInfo(JCSMPSession session, XMLMessageConsumer consumer, FlowReceiver flowReceiver) {
            this.session = session;
            this.consumer = consumer;
            this.flowReceiver = flowReceiver;
        }

        /**
         * 關閉所有關聯的消費者資源。
         */
        public void close() {
            if (flowReceiver != null) {
                flowReceiver.stop();
            }
            if (consumer != null) {
                consumer.close();
            }
            if (session != null && !session.isClosed()) {
                session.closeSession();
            }
        }
    }
}
