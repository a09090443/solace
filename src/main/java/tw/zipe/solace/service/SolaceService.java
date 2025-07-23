package tw.zipe.solace.service;

import com.solacesystems.jcsmp.*;
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

    private SolaceConnectionPool producerPool;
    private JCSMPSession consumerSession;

    // 追蹤消費者和 Flow 以便優雅地關閉
    private final List<XMLMessageConsumer> consumers = new ArrayList<>();
    private final List<FlowReceiver> flowReceivers = new ArrayList<>();

    // 用於緩存從 Topic 和 Queue 收到的文字訊息
    private final Map<String, ConcurrentLinkedQueue<String>> topicMessagesCache = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> queueMessagesCache = new ConcurrentHashMap<>();

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

        // 建立並連接消費者專用的 Session
        try {
            consumerSession = JCSMPFactory.onlyInstance().createSession(properties);
            consumerSession.connect();
            logger.info("Solace 消費者連線已成功建立。");
        } catch (JCSMPException e) {
            logger.error("建立消費者連線失敗。", e);
            throw e;
        }
    }

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
        return properties;
    }

    @PreDestroy
    public void close() {
        logger.info("正在關閉 Solace 資源...");

        // 關閉消費者資源
        for (FlowReceiver flow : flowReceivers) {
            flow.stop();
        }
        for (XMLMessageConsumer consumer : consumers) {
            consumer.close();
        }
        if (consumerSession != null && !consumerSession.isClosed()) {
            consumerSession.closeSession();
            logger.info("Solace 消費者連線已關閉。");
        }

        // 關閉生產者連線池
        if (producerPool != null) {
            producerPool.close();
            logger.info("Solace 生產者連線池已關閉。");
        }
    }

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

    public void subscribeToTopic(String topicName) throws JCSMPException {
        Topic topic = JCSMPFactory.onlyInstance().createTopic(topicName);
        XMLMessageConsumer consumer = consumerSession.getMessageConsumer(new XMLMessageListener() {
            @Override
            public void onReceive(BytesXMLMessage msg) {
                handleReceivedMessage(msg, topicName, DestinationType.TOPIC);
            }

            @Override
            public void onException(JCSMPException e) {
                logger.error("消費者在 Topic {} 上發生異常: {}", topicName, e.getMessage(), e);
            }
        });
        consumerSession.addSubscription(topic);
        consumer.start();
        consumers.add(consumer);
        logger.info("已成功訂閱 Topic: {}", topicName);
    }

    public void receiveFromQueue(String queueName) throws JCSMPException {
        Queue queue = JCSMPFactory.onlyInstance().createQueue(queueName);
        ConsumerFlowProperties flowProps = new ConsumerFlowProperties();
        flowProps.setEndpoint(queue);
        flowProps.setAckMode(JCSMPProperties.SUPPORTED_MESSAGE_ACK_CLIENT);

        FlowReceiver flowReceiver = consumerSession.createFlow(new XMLMessageListener() {
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
        flowReceivers.add(flowReceiver);
        logger.info("已開始監聽 Queue: {}", queueName);
    }

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

    public List<String> getAndClearTopicMessages(String topicName) {
        return getAndClearMessagesFromCache(topicMessagesCache, topicName);
    }

    public List<String> getAndClearQueueMessages(String queueName) {
        return getAndClearMessagesFromCache(queueMessagesCache, queueName);
    }

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

    public enum DestinationType {
        TOPIC, QUEUE
    }

    public String getDefaultTopic() {
        return defaultTopic;
    }

    public String getDefaultQueue() {
        return defaultQueue;
    }
}

