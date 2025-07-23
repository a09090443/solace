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
import java.time.Instant;
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

    @Value("${solace.jms.client-password:}")
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

    @Value("${solace.jms.ssl.key-store:#{null}}")
    private String keyStore;

    @Value("${solace.jms.ssl.key-store-password:#{null}}")
    private String keyStorePassword;

    @Value("${solace.jms.ssl.key-store-format:#{null}}")
    private String keyStoreFormat;


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
     *
     * @throws JCSMPException 如果連線或初始化過程中發生錯誤。
     */
    @PostConstruct
    public void initialize() throws JCSMPException {
        logger.info("正在初始化 Solace 連線...");

        // --- Host 協定轉換 (smf -> tcp) ---
        String connectionHost = this.host;
        if (connectionHost != null && (connectionHost.startsWith("smf://") || connectionHost.startsWith("smfs://"))) {
            // Test Case 6.1, 6.4: Failover connection logging
            logger.info("TEST_CASE_6.1/6.4_FAILOVER | 偵測到 'smf(s)://' 協定，將自動轉換為 'tcp(s)://'。原始 host: {} | MsgVPN: {} | ClientUser: {}",
                    this.host, vpnName, username);
            connectionHost = connectionHost.replace("smf://", "tcp://").replace("smfs://", "tcps://");
            logger.info("TEST_CASE_6.1/6.4_FAILOVER | 轉換後 host: {} | MsgVPN: {} | ClientUser: {}",
                    connectionHost, vpnName, username);
        }

        final JCSMPProperties properties = new JCSMPProperties();
        properties.setProperty(JCSMPProperties.HOST, connectionHost);
        properties.setProperty(JCSMPProperties.USERNAME, username);
        properties.setProperty(JCSMPProperties.VPN_NAME, vpnName);

        // --- 認證與 SSL/TLS 設定 ---
        String lowerCaseHost = (connectionHost != null) ? connectionHost.toLowerCase() : "";
        boolean isSecureConnection = lowerCaseHost.contains("tcps://");
        boolean useClientCertificateAuth = isSecureConnection && keyStore != null && !keyStore.isEmpty();

        if (isSecureConnection) {
            logger.info("偵測到安全連線 (tcps://)，正在設定 SSL 屬性...");

            // 1. 設定 TrustStore (用於信任 Broker)
            if (trustStore != null && !trustStore.isEmpty()) {
                properties.setProperty(JCSMPProperties.SSL_TRUST_STORE, trustStore);
                if (trustStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_TRUST_STORE_PASSWORD, trustStorePassword);
                }
                logger.info("已設定自訂 TrustStore: {}", trustStore);
            }

            // 2. 根據是否存在 KeyStore 決定認證方式
            if (useClientCertificateAuth) {
                // 使用客戶端憑證驗證
                logger.info("偵測到 KeyStore 設定，將使用客戶端憑證進行驗證。");
                properties.setProperty(JCSMPProperties.SSL_KEY_STORE, keyStore);
                if (keyStorePassword != null) {
                    properties.setProperty(JCSMPProperties.SSL_KEY_STORE_PASSWORD, keyStorePassword);
                }
                if (keyStoreFormat != null) {
                    properties.setProperty(JCSMPProperties.SSL_KEY_STORE_FORMAT, keyStoreFormat);
                }
                properties.setProperty(JCSMPProperties.AUTHENTICATION_SCHEME, JCSMPProperties.AUTHENTICATION_SCHEME_CLIENT_CERTIFICATE);
            } else {
                // 使用密碼驗證
                logger.info("未偵測到 KeyStore 設定，將使用密碼進行驗證。");
                properties.setProperty(JCSMPProperties.PASSWORD, password);
            }

            // 3. 設定憑證驗證開關
            properties.setBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE, validateCertificate);
            if (!validateCertificate) {
                logger.warn("!!! 安全性警告: Solace 連線已停用憑證驗證 (SSL_VALIDATE_CERTIFICATE=false)。請勿在生產環境中使用此設定。");
            }
        } else {
            // 非安全連線，只能使用密碼驗證
            logger.info("未使用安全連線，將使用密碼進行驗證。");
            properties.setProperty(JCSMPProperties.PASSWORD, password);
        }

        // =================================================================================
        // [新增] 連線前最終檢查：將所有重要的連線參數打印到日誌中，以便除錯。
        // =================================================================================
        logger.info("=================== Solace Connection Properties (Before Connect) ===================");
        logger.info("HOST: {}", properties.getProperty(JCSMPProperties.HOST));
        logger.info("VPN_NAME: {}", properties.getProperty(JCSMPProperties.VPN_NAME));
        logger.info("USERNAME: {}", properties.getProperty(JCSMPProperties.USERNAME));

        if (useClientCertificateAuth) {
            logger.info("AUTHENTICATION_SCHEME: {}", properties.getProperty(JCSMPProperties.AUTHENTICATION_SCHEME));
            logger.info("SSL_KEY_STORE: {}", properties.getProperty(JCSMPProperties.SSL_KEY_STORE));
            logger.info("SSL_KEY_STORE_FORMAT: {}", properties.getProperty(JCSMPProperties.SSL_KEY_STORE_FORMAT));
            logger.info("SSL_KEY_STORE_PASSWORD: {}", (keyStorePassword != null && !keyStorePassword.isEmpty()) ? "********" : "(not set)");
        } else {
            logger.info("AUTHENTICATION_SCHEME: (Using Basic/Password)");
            logger.info("PASSWORD: {}", (password != null && !password.isEmpty()) ? "********" : "(not set or empty)");
        }

        if (isSecureConnection) {
            logger.info("SSL_TRUST_STORE: {}", properties.getProperty(JCSMPProperties.SSL_TRUST_STORE));
            logger.info("SSL_TRUST_STORE_PASSWORD: {}", (trustStorePassword != null && !trustStorePassword.isEmpty()) ? "********" : "(not set)");
            logger.info("SSL_VALIDATE_CERTIFICATE: {}", properties.getBooleanProperty(JCSMPProperties.SSL_VALIDATE_CERTIFICATE));
        }
        logger.info("===================================================================================");
        logger.info("準備執行 session.connect()...");


        try {
            session = JCSMPFactory.onlyInstance().createSession(properties);

            // Test Case 8: Server connection timing
            long connectStartTime = System.currentTimeMillis();
            session.connect();
            long connectEndTime = System.currentTimeMillis();

            // Test Case 8: Solace server flip test - connection timing
            logger.info("TEST_CASE_8_SERVER_FLIP | Solace 連線建立成功 | ConnectionTime: {}ms | Host: {} | MsgVPN: {} | ClientUser: {}",
                    (connectEndTime - connectStartTime), connectionHost, vpnName, username);

            // Test Case 11: Reuse connection via sessions
            logger.info("TEST_CASE_11_SESSION_REUSE | Session 已建立，準備重複使用此連線 | SessionID: {} | MsgVPN: {} | ClientUser: {}",
                    session.getSessionName(), vpnName, username);

        } catch (JCSMPException e) {
            // Test Case 7.2: Connection failure logging
            logger.error("TEST_CASE_7.2_CONNECTION_FAILURE | Solace 連線失敗 | Host: {} | MsgVPN: {} | ClientUser: {} | ErrorCode: {} | Timestamp: {} | Exception: {}",
                    connectionHost, vpnName, username, e.getClass().getSimpleName(), Instant.now().toEpochMilli(), e.getMessage(), e);
            throw e;
        }

        producer = session.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
            public void responseReceived(String messageID) {
                // Test Case 1.1, 7.1: Publisher success with all required fields
                logger.info("TEST_CASE_1.1_7.1_PUBLISH_SUCCESS | MessageID: {} | Timestamp: {} | ClientUser: {} | MsgVPN: {} | ConversationID: N/A",
                        messageID, Instant.now().toEpochMilli(), username, vpnName);
            }

            public void handleError(String messageID, JCSMPException e, long timestamp) {
                // Test Case 7.2: Publisher failure with detailed error information
                String errorCode = e.getClass().getSimpleName();
                String subcode = "N/A";
                if (e instanceof com.solacesystems.jcsmp.JCSMPErrorResponseException) {
                    subcode = String.valueOf(((com.solacesystems.jcsmp.JCSMPErrorResponseException) e).getSubcode());
                }
                logger.error("TEST_CASE_7.2_PUBLISH_FAILURE | MessageID: {} | Timestamp: {} | ClientUser: {} | MsgVPN: {} | ErrorCode: {} | SubCode: {} | Exception: {}",
                        messageID, timestamp, username, vpnName, errorCode, subcode, e.getMessage(), e);

                // Test Case 6.2: Reconnect & retry for sender
                logger.warn("TEST_CASE_6.2_SENDER_RETRY | 發送端將嘗試重新連線並重試 | MessageID: {} | ClientUser: {} | MsgVPN: {}",
                        messageID, username, vpnName);
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

        // Test Case 10: Set DMQ Eligibility to TRUE
        msg.setDMQEligible(true);
        // Test Case 6.6: Set Correlation ID if available (for request-reply)
        // In a real scenario, you might pass this in as a parameter.
        // msg.setCorrelationId("req-123");

        // Test Case 6.3: Send mandatory JMS headers
        msg.setApplicationMessageId("APP-" + System.currentTimeMillis());
        msg.setApplicationMessageType("TEXT_MESSAGE");
        msg.setSenderTimestamp(System.currentTimeMillis());

        // Test Case 1.2: Dynamic topic generation
        if (type == DestinationType.TOPIC) {
            logger.info("TEST_CASE_1.2_DYNAMIC_TOPIC | 動態產生Topic | DestinationName: {} | Timestamp: {} | ClientUser: {} | MsgVPN: {}",
                    destinationName, System.currentTimeMillis(), username, vpnName);
        }

        // Test Case 7.1, 6.3: Pre-send logging with mandatory JMS headers
        logger.info("TEST_CASE_7.1_6.3_PUBLISH_ATTEMPT | Destination: {} | DestinationType: {} | AppMessageID: {} | SenderTimestamp: {} | DMQEligible: {} | ClientUser: {} | MsgVPN: {} | Body: {}",
                destination.getName(), type, msg.getApplicationMessageId(), msg.getSenderTimestamp(), msg.isDMQEligible(), username, vpnName, message);

        // Test Case 9.1: Publisher transaction logging (Note: JCSMP uses different transaction handling)
        logger.info("TEST_CASE_9.1_PUBLISHER_TRANSACTION | 準備發送訊息 | Destination: {} | ClientUser: {} | MsgVPN: {}",
                destination.getName(), username, vpnName);

        producer.send(msg, destination);

        // Test Case 1.1: Application client as sender - Record JMSMessageID, JMSCorrelationID, JMSTimestamp
        logger.info("TEST_CASE_1.1_SENDER_COMPLETE | JMSMessageID: {} | JMSCorrelationID: {} | JMSTimestamp: {} | Destination: {} | DestinationType: {} | ClientUser: {} | MsgVPN: {} | AppMessageID: {}",
                msg.getMessageId(),
                (msg.getCorrelationId() != null ? msg.getCorrelationId() : "N/A"),
                msg.getSenderTimestamp(),
                destination.getName(),
                type,
                username,
                vpnName,
                msg.getApplicationMessageId());

        // Test Case 9.1: Transaction commit simulation (JCSMP handles transactions differently)
        logger.info("TEST_CASE_9.1_PUBLISHER_COMMIT | 訊息已發送 | Destination: {} | Timestamp: {} | ClientUser: {} | MsgVPN: {}",
                destination.getName(), System.currentTimeMillis(), username, vpnName);
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

        // Test Case 10: Set DMQ Eligibility to TRUE
        msg.setDMQEligible(true);

        // Test Case 6.3: Send mandatory JMS headers for file
        msg.setApplicationMessageId("FILE-" + System.currentTimeMillis());
        msg.setApplicationMessageType("FILE_MESSAGE");
        msg.setSenderTimestamp(System.currentTimeMillis());

        // Test Case 7.1: Publisher Success Log (Pre-send)
        logger.info("TEST_CASE_7.1_FILE_PUBLISH_ATTEMPT | Destination: {} | DestinationType: {} | AppMessageID: {} | SenderTimestamp: {} | DMQEligible: {} | FileName: {} | FileSize: {} | ClientUser: {} | MsgVPN: {}",
                destination.getName(), type, msg.getApplicationMessageId(), msg.getSenderTimestamp(), msg.isDMQEligible(), fileName, fileData.length, username, vpnName);

        producer.send(msg, destination);

        // Test Case 1.1: Application client as sender for file - Record JMSMessageID, JMSCorrelationID, JMSTimestamp
        logger.info("TEST_CASE_1.1_FILE_SENDER_COMPLETE | JMSMessageID: {} | JMSCorrelationID: {} | JMSTimestamp: {} | Destination: {} | DestinationType: {} | FileName: {} | FileSize: {} | ClientUser: {} | MsgVPN: {} | AppMessageID: {}",
                msg.getMessageId(),
                (msg.getCorrelationId() != null ? msg.getCorrelationId() : "N/A"),
                msg.getSenderTimestamp(),
                destination.getName(),
                type,
                fileName,
                fileData.length,
                username,
                vpnName,
                msg.getApplicationMessageId());
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

        // Test Case 2.1 vs 2.2: Acknowledged vs Unacknowledged
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);
        logger.info("TEST_CASE_2.1_ACKNOWLEDGED_MODE | Queue消費者設定為確認模式 | Queue: {} | ClientUser: {} | MsgVPN: {}",
                queueName, username, vpnName);

        FlowReceiver flowReceiver = session.createFlow(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                handleReceivedMessage(msg, queueName, DestinationType.QUEUE);

                // Test Case 2.1: Message acknowledgment
                msg.ackMessage();
                logger.info("TEST_CASE_2.1_MESSAGE_ACKNOWLEDGED | MessageID: {} | Queue: {} | Timestamp: {} | ClientUser: {} | MsgVPN: {}",
                        msg.getMessageId(), queueName, System.currentTimeMillis(), username, vpnName);

                // Test Case 9.2: Consumer transaction logging (JCSMP handles transactions differently)
                logger.info("TEST_CASE_9.2_CONSUMER_COMMIT | 消費者已處理訊息 | MessageID: {} | Queue: {} | ClientUser: {} | MsgVPN: {}",
                        msg.getMessageId(), queueName, username, vpnName);
            }

            @Override
            public void onException(JCSMPException e) {
                // Test Case 7.2: Consumer exception logging
                logger.error("TEST_CASE_7.2_CONSUMER_EXCEPTION | Queue: {} | ClientUser: {} | MsgVPN: {} | ErrorCode: {} | Timestamp: {} | Exception: {}",
                        queueName, username, vpnName, e.getClass().getSimpleName(), System.currentTimeMillis(), e.getMessage(), e);

                // Test Case 6.5: Reconnect & retry for subscriber
                logger.warn("TEST_CASE_6.5_SUBSCRIBER_RETRY | 訂閱端將嘗試重新連線 | Queue: {} | ClientUser: {} | MsgVPN: {}",
                        queueName, username, vpnName);
            }
        }, flowProps);

        flowReceiver.start();
        flowReceivers.add(flowReceiver);

        // Test Case 11: Session reuse logging
        logger.info("TEST_CASE_11_SESSION_REUSE | 重複使用現有連線建立Queue監聽器 | Queue: {} | SessionID: {} | ClientUser: {} | MsgVPN: {}",
                queueName, session.getSessionName(), username, vpnName);

        logger.info("已開始監聽 Queue: {}", queueName);
    }

    /**
     * 統一處理接收到的訊息 (包括文字和檔案)。
     *
     * @param msg    接收到的原始 Solace 訊息。
     * @param source 訊息來源的名稱 (Topic 或 Queue)。
     */
    private void handleReceivedMessage(BytesXMLMessage msg, String source, DestinationType type) {
        // Test Case 6.6: CorrelationID handling for request-response
        String originalMessageId = msg.getMessageId();
        String correlationId = msg.getCorrelationId();

        if (msg instanceof TextMessage) {
            String messageText = ((TextMessage) msg).getText();

            // Test Case 2.1: Duplicate message check
            logger.info("TEST_CASE_2.1_DUPLICATE_CHECK | 檢查重複訊息 | MessageID: {} | Source: {} | ClientUser: {} | MsgVPN: {}",
                    originalMessageId, source, username, vpnName);

            // Test Case 7.1: Consumer success with all required fields
            logger.info("TEST_CASE_7.1_CONSUME_SUCCESS | MessageID: {} | CorrelationID: {} | Timestamp: {} | SenderTimestamp: {} | Destination: {} | DestinationType: {} | ClientUser: {} | MsgVPN: {} | ConversationID: {} | Body: {}",
                    originalMessageId, correlationId, System.currentTimeMillis(), msg.getSenderTimestamp(), source, type, username, vpnName,
                    (correlationId != null ? correlationId : "N/A"), messageText);

            // Test Case 6.6: Prepare response with correlation ID
            if (originalMessageId != null) {
                logger.info("TEST_CASE_6.6_CORRELATION_RESPONSE | 準備回應訊息 | OriginalMessageID: {} | WillSetAsCorrelationID: {} | Source: {} | ClientUser: {} | MsgVPN: {}",
                        originalMessageId, originalMessageId, source, username, vpnName);
            }

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
                if (properties == null || !properties.containsKey("FILE_NAME")) {
                    logger.warn("收到的檔案訊息缺少 \"FILE_NAME\" 屬性，將忽略此訊息。來源: {}", source);
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

                long fileSize = buffer.remaining(); // Get file size from buffer
                String successMessage = String.format("檔案 '%s' 已成功接收並儲存至: %s", fileName, outputPath.toAbsolutePath());

                // Test Case 7.1: File processing success
                logger.info("TEST_CASE_7.1_FILE_CONSUME_SUCCESS | MessageID: {} | CorrelationID: {} | Timestamp: {} | SenderTimestamp: {} | Destination: {} | DestinationType: {} | ClientUser: {} | MsgVPN: {} | FileName: {} | FilePath: {} | FileSize: {}",
                        originalMessageId, correlationId, System.currentTimeMillis(), msg.getSenderTimestamp(), source, type, username, vpnName, fileName, outputPath.toAbsolutePath(), fileSize);

                // 根據目的地類型將檔案接收成功訊息存入對應的快取
                if (type == DestinationType.TOPIC) {
                    topicMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(successMessage);
                } else { // QUEUE
                    queueMessagesCache.computeIfAbsent(source, k -> new ConcurrentLinkedQueue<>()).add(successMessage);
                }
            } catch (IOException | JCSMPException e) {
                // Test Case 7.2: File processing failure
                logger.error("TEST_CASE_7.2_FILE_CONSUME_FAILURE | MessageID: {} | Destination: {} | DestinationType: {} | ClientUser: {} | MsgVPN: {} | Timestamp: {} | ErrorType: {} | Exception: {}",
                        originalMessageId, source, type, username, vpnName, System.currentTimeMillis(), e.getClass().getSimpleName(), e.getMessage(), e);
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
     *
     * @return 預設 Topic 名稱。
     */
    public String getDefaultTopic() {
        return defaultTopic;
    }

    /**
     * 獲取設定檔中設定的預設 Queue 名稱。
     *
     * @return 預設 Queue 名稱。
     */
    public String getDefaultQueue() {
        return defaultQueue;
    }
}

