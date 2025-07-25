package tw.zipe.solace.service;

import org.springframework.stereotype.Service;
import tw.zipe.solace.config.SolaceProperties;

import java.util.List;

/**
 * Solace 核心服務類別。
 * <p>
 * 作為一個外觀 (Facade)，整合了 {@link SolaceProducerService} 和 {@link SolaceConsumerService} 的功能，
 * 為 {@link tw.zipe.solace.controller.SolaceController} 提供一個統一的介面來發送和接收訊息。
 * 同時也提供對預設 Topic 和 Queue 名稱的存取。
 * </p>
 */
@Service
public class SolaceService {

    private final SolaceProducerService producerService;
    private final SolaceConsumerService consumerService;
    private final SolaceProperties solaceProperties;

    /**
     * 建構一個新的 SolaceService。
     *
     * @param producerService 訊息生產者服務。
     * @param consumerService 訊息消費者服務。
     * @param solaceProperties Solace 組態屬性。
     */
    public SolaceService(SolaceProducerService producerService, SolaceConsumerService consumerService, SolaceProperties solaceProperties) {
        this.producerService = producerService;
        this.consumerService = consumerService;
        this.solaceProperties = solaceProperties;
    }

    /**
     * 發送文字訊息到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地名稱。
     * @param type 目的地類型 ({@link DestinationType#TOPIC} 或 {@link DestinationType#QUEUE})。
     * @param message 要發送的文字訊息。
     * @throws Exception 如果發送過程中發生錯誤。
     */
    public void sendTextMessage(String destinationName, DestinationType type, String message) throws Exception {
        producerService.sendTextMessage(destinationName, type, message);
    }

    /**
     * 發送檔案到指定的目的地 (Topic 或 Queue)。
     *
     * @param destinationName 目的地名稱。
     * @param type 目的地類型 ({@link DestinationType#TOPIC} 或 {@link DestinationType#QUEUE})。
     * @param fileName 檔案名稱。
     * @param fileData 檔案的位元組陣列。
     * @throws Exception 如果發送過程中發生錯誤。
     */
    public void sendFile(String destinationName, DestinationType type, String fileName, byte[] fileData) throws Exception {
        producerService.sendFile(destinationName, type, fileName, fileData);
    }

    /**
     * 訂閱一個 Topic 以接收訊息。
     *
     * @param topicName 要訂閱的 Topic 名稱。
     * @throws Exception 如果訂閱過程中發生錯誤。
     */
    public void subscribeToTopic(String topicName) throws Exception {
        consumerService.subscribeToTopic(topicName);
    }

    /**
     * 從指定的 Queue 接收訊息。
     *
     * @param queueName 要接收訊息的 Queue 名稱。
     * @throws Exception 如果接收過程中發生錯誤。
     */
    public void receiveFromQueue(String queueName) throws Exception {
        consumerService.receiveFromQueue(queueName);
    }

    /**
     * 獲取並清除指定 Topic 接收到的所有訊息。
     *
     * @param topicName Topic 名稱。
     * @return 訊息列表。
     */
    public List<String> getAndClearTopicMessages(String topicName) {
        return consumerService.getAndClearTopicMessages(topicName);
    }

    /**
     * 獲取並清除指定 Queue 接收到的所有訊息。
     *
     * @param queueName Queue 名稱。
     * @return 訊息列表。
     */
    public List<String> getAndClearQueueMessages(String queueName) {
        return consumerService.getAndClearQueueMessages(queueName);
    }

    /**
     * 獲取預設的 Topic 名稱。
     *
     * @return 預設 Topic 名稱。
     */
    public String getDefaultTopic() {
        return solaceProperties.getDefaults().getTopic();
    }

    /**
     * 獲取預設的 Queue 名稱。
     *
     * @return 預設 Queue 名稱。
     */
    public String getDefaultQueue() {
        return solaceProperties.getDefaults().getQueue();
    }

    /**
     * 表示 Solace 訊息目的地的類型。
     */
    public enum DestinationType {
        TOPIC, QUEUE
    }
}
