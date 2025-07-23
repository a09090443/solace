package tw.zipe.solace.controller;

import com.solacesystems.jcsmp.JCSMPException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;
import tw.zipe.solace.service.SolaceService;

import java.util.List;

/**
 * Solace 訊息發布與訂閱的 RESTful API 控制器
 * <p>
 * 提供 HTTP 端點來與 Solace 訊息系統進行互動，包括發送文字訊息、上傳檔案、訂閱 Topic 以及監聽 Queue。
 * </p>
 */
@RestController
@RequestMapping("/api/solace")
public class SolaceController {

    private static final Logger logger = LoggerFactory.getLogger(SolaceController.class);
    private final SolaceService solaceService;

    @Autowired
    public SolaceController(SolaceService solaceService) {
        this.solaceService = solaceService;
    }

    private String extractPath(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (bestMatchPattern != null) {
            String prefix = bestMatchPattern.replace("/**", "");
            String subPath = path.substring(prefix.length());
            return subPath.startsWith("/") ? subPath.substring(1) : subPath;
        }
        return null;
    }

    private String getTopicName(HttpServletRequest request) {
        String topicName = extractPath(request);
        if (topicName == null || topicName.isEmpty()) {
            return solaceService.getDefaultTopic();
        }
        return topicName;
    }

    private String getQueueName(HttpServletRequest request) {
        String queueName = extractPath(request);
        if (queueName == null || queueName.isEmpty()) {
            return solaceService.getDefaultQueue();
        }
        return queueName;
    }

    /**
     * 發布文字訊息到指定的 Topic。
     *
     * @param request HTTP 請求物件，用於提取 Topic 名稱。
     * @param message 要發送的文字訊息內容。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/topic", "/topic/**"})
    public ResponseEntity<String> publishToTopic(HttpServletRequest request, @RequestBody String message) throws Exception {
        String topicName = getTopicName(request);

        // Test Case 1.1: Application client as sender - Start logging
        logger.info("TEST_CASE_1.1_SENDER_START | 應用程式用戶端作為發送端開始 | TopicName: {} | MessageLength: {} | Timestamp: {}",
                topicName, message.length(), System.currentTimeMillis());

        // Test Case 1.2: Dynamic topic generation based on request
        logger.info("TEST_CASE_1.2_DYNAMIC_TOPIC_REQUEST | 動態Topic請求 | TopicName: {} | RequestURI: {} | Timestamp: {}",
                topicName, request.getRequestURI(), System.currentTimeMillis());

        logger.info("接收到發布訊息到 Topic '{}' 的請求。", topicName);
        solaceService.sendTextMessage(topicName, SolaceService.DestinationType.TOPIC, message);
        String responseMessage = "訊息已成功發送到 Topic: " + topicName;
        logger.info("成功處理發布訊息到 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 發布文字訊息到指定的 Queue。
     *
     * @param request HTTP 請求物件，用於提取 Queue 名稱。
     * @param message 要發送的文字訊息內容。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/queue", "/queue/**"})
    public ResponseEntity<String> publishToQueue(HttpServletRequest request, @RequestBody String message) throws Exception {
        String queueName = getQueueName(request);

        // Test Case 1.1: Application client as sender - Start logging for Queue
        logger.info("TEST_CASE_1.1_SENDER_START | 應用程式用戶端作為發送端開始 | QueueName: {} | MessageLength: {} | Timestamp: {}",
                queueName, message.length(), System.currentTimeMillis());

        logger.info("接收到發布訊息到 Queue '{}' 的請求。", queueName);
        solaceService.sendTextMessage(queueName, SolaceService.DestinationType.QUEUE, message);
        String responseMessage = "訊息已成功發送到 Queue: " + queueName;
        logger.info("成功處理發布訊息到 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 上傳檔案並將其作為訊息發送到指定的 Topic。
     *
     * @param request HTTP 請求物件，用於提取 Topic 名稱。
     * @param file    要上傳的檔案。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/topic/file", "/topic/file/**"})
    public ResponseEntity<String> uploadFileToTopic(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        String topicName = getTopicName(request);

        // Test Case 1.1: Application client as sender for file
        logger.info("TEST_CASE_1.1_FILE_SENDER_START | 應用程式用戶端檔案發送開始 | TopicName: {} | FileName: {} | FileSize: {} | Timestamp: {}",
                topicName, file.getOriginalFilename(), file.getSize(), System.currentTimeMillis());

        logger.info("接收到上傳檔案 '{}' 到 Topic '{}' 的請求。", file.getOriginalFilename(), topicName);
        solaceService.sendFile(topicName, SolaceService.DestinationType.TOPIC, file.getOriginalFilename(), file.getBytes());
        String responseMessage = "檔案已成功發送到 Topic: " + topicName;
        logger.info("成功處理上傳檔案到 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 上傳檔案並將其作為訊息發送到指定的 Queue。
     *
     * @param request HTTP 請求物件，用於提取 Queue 名稱。
     * @param file    要上傳的檔案。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/queue/file", "/queue/file/**"})
    public ResponseEntity<String> uploadFileToQueue(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws Exception {
        String queueName = getQueueName(request);

        // Test Case 1.1: Application client as sender for file
        logger.info("TEST_CASE_1.1_FILE_SENDER_START | 應用程式用戶端檔案發送開始 | QueueName: {} | FileName: {} | FileSize: {} | Timestamp: {}",
                queueName, file.getOriginalFilename(), file.getSize(), System.currentTimeMillis());

        logger.info("接收到上傳檔案 '{}' 到 Queue '{}' 的請求。", file.getOriginalFilename(), queueName);
        solaceService.sendFile(queueName, SolaceService.DestinationType.QUEUE, file.getOriginalFilename(), file.getBytes());
        String responseMessage = "檔案已成功發送到 Queue: " + queueName;
        logger.info("成功處理上傳檔案到 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 訂閱一個 Topic 以開始接收訊息。
     *
     * @param request HTTP 請求物件，用於提取 Topic 名稱。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/subscribe/topic", "/subscribe/topic/**"})
    public ResponseEntity<String> subscribeToTopic(HttpServletRequest request) throws JCSMPException {
        String topicName = getTopicName(request);

        // Test Case 11: Session reuse for multiple subscriptions
        logger.info("TEST_CASE_11_MULTIPLE_SUBSCRIPTIONS | 使用現有連線建立新的Topic訂閱 | TopicName: {} | RequestCount: {}",
                topicName, System.currentTimeMillis() % 1000); // Simple request counter simulation

        logger.info("接收到訂閱 Topic '{}' 的請求。", topicName);
        solaceService.subscribeToTopic(topicName);
        String responseMessage = "已成功訂閱 Topic: " + topicName;
        logger.info("成功處理訂閱 Topic '{}' 的請求。", topicName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 開始監聽一個 Queue 以接收訊息。
     *
     * @param request HTTP 請求物件，用於提取 Queue 名稱。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/listen/queue", "/listen/queue/**"})
    public ResponseEntity<String> listenToQueue(HttpServletRequest request) throws JCSMPException {
        String queueName = getQueueName(request);
        logger.info("接收到監聽 Queue '{}' 的請求。", queueName);
        solaceService.receiveFromQueue(queueName);
        String responseMessage = "已開始監聽 Queue: " + queueName;
        logger.info("成功處理監聽 Queue '{}' 的請求。", queueName);
        return ResponseEntity.ok(responseMessage);
    }

    /**
     * 獲取從指定 Queue 監聽到的訊息。
     * <p>
     * 此端點會返回自上次調用以來在該 Queue 上累積的所有訊息，
     * 包括文字訊息和檔案接收成功的通知。
     * </p>
     *
     * @param request HTTP 請求物件，用於提取 Queue 名稱。
     * @return ResponseEntity 包含一個訊息字串列表。
     */
    @GetMapping(value = {"/messages/queue", "/messages/queue/**"})
    public ResponseEntity<List<String>> getQueueMessages(HttpServletRequest request) {
        String queueName = getQueueName(request);
        logger.info("接收到獲取 Queue '{}' 訊息的請求。", queueName);
        List<String> messages = solaceService.getAndClearQueueMessages(queueName);
        logger.info("成功獲取到 {} 條來自 Queue '{}' 的訊息。", messages.size(), queueName);
        return ResponseEntity.ok(messages);
    }

    /**
     * 獲取從指定 Topic 訂閱到的訊息。
     * <p>
     * 此端點會返回自上次調用以來在該 Topic 上累積的所有訊息，
     * 包括文字訊息和檔案接收成功的通知。
     * </p>
     *
     * @param request HTTP 請求物件，用於提取 Topic 名稱。
     * @return ResponseEntity 包含一個訊息字串列表。
     */
    @GetMapping(value = {"/messages/topic", "/messages/topic/**"})
    public ResponseEntity<List<String>> getTopicMessages(HttpServletRequest request) {
        String topicName = getTopicName(request);
        logger.info("接收到獲取 Topic '{}' 訊息的請求。", topicName);
        List<String> messages = solaceService.getAndClearTopicMessages(topicName);
        logger.info("成功獲取到 {} 條來自 Topic '{}' 的訊息。", messages.size(), topicName);
        return ResponseEntity.ok(messages);
    }
}
