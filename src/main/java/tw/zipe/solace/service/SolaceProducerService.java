package tw.zipe.solace.service;

import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishEventHandler;
import com.solacesystems.jcsmp.SDTMap;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tw.zipe.solace.config.SolaceProperties;

/**
 * Solace 訊息生產者服務。
 * <p>
 * 負責將訊息和檔案發布到 Solace 訊息代理的 Topic 或 Queue。
 * 使用 {@link SolaceConnectionPool} 來管理 JCSMP Session，以實現高效的資源利用。
 * </p>
 */
@Service
public class SolaceProducerService {

    private static final Logger logger = LoggerFactory.getLogger(SolaceProducerService.class);

    private final SolaceConnectionPool producerPool;

    /**
     * 建構一個新的 SolaceProducerService。
     *
     * @param solaceProperties Solace 組態屬性，用於初始化連線池。
     */
    public SolaceProducerService(SolaceProperties solaceProperties) {
        this.producerPool = new SolaceConnectionPool(new SolaceSessionFactory(solaceProperties), solaceProperties.getPool());
    }

    /**
     * 發送文字訊息到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地名稱。
     * @param type 目的地類型 ({@link SolaceService.DestinationType#TOPIC} 或 {@link SolaceService.DestinationType#QUEUE})。
     * @param message 要發送的文字訊息。
     * @throws Exception 如果發送過程中發生錯誤。
     */
    public void sendTextMessage(String destinationName, SolaceService.DestinationType type, String message) throws Exception {
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

            Destination destination = type == SolaceService.DestinationType.TOPIC ?
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
     *
     * @param destinationName 目的地名稱。
     * @param type 目的地類型 ({@link SolaceService.DestinationType#TOPIC} 或 {@link SolaceService.DestinationType#QUEUE})。
     * @param fileName 檔案名稱，將作為訊息屬性發送。
     * @param fileData 檔案的位元組陣列，將作為訊息附件發送。
     * @throws Exception 如果發送過程中發生錯誤。
     */
    public void sendFile(String destinationName, SolaceService.DestinationType type, String fileName, byte[] fileData) throws Exception {
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

            Destination destination = type == SolaceService.DestinationType.TOPIC ?
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
}
