package tw.zipe.solace.controller;

import com.solacesystems.jcsmp.JCSMPException;
import jakarta.servlet.http.HttpServletRequest;
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

import java.io.IOException;
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
     * @param request   HTTP 請求物件，用於提取 Topic 名稱。
     * @param message   要發送的文字訊息內容。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/topic", "/topic/**"})
    public ResponseEntity<String> publishToTopic(HttpServletRequest request, @RequestBody String message) throws JCSMPException {
        String topicName = getTopicName(request);
        solaceService.sendTextMessage(topicName, SolaceService.DestinationType.TOPIC, message);
        return ResponseEntity.ok("訊息已成功發送到 Topic: " + topicName);
    }

    /**
     * 發布文字訊息到指定的 Queue。
     *
     * @param request   HTTP 請求物件，用於提取 Queue 名稱。
     * @param message   要發送的文字訊息內容。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/queue", "/queue/**"})
    public ResponseEntity<String> publishToQueue(HttpServletRequest request, @RequestBody String message) throws JCSMPException {
        String queueName = getQueueName(request);
        solaceService.sendTextMessage(queueName, SolaceService.DestinationType.QUEUE, message);
        return ResponseEntity.ok("訊息已成功發送到 Queue: " + queueName);
    }

    /**
     * 上傳檔案並將其作為訊息發送到指定的 Topic。
     *
     * @param request   HTTP 請求物件，用於提取 Topic 名稱。
     * @param file      要上傳的檔案。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/topic/file", "/topic/file/**"})
    public ResponseEntity<String> uploadFileToTopic(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws JCSMPException, IOException {
        String topicName = getTopicName(request);
        solaceService.sendFile(topicName, SolaceService.DestinationType.TOPIC, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.ok("檔案已成功發送到 Topic: " + topicName);
    }

    /**
     * 上傳檔案並將其作為訊息發送到指定的 Queue。
     *
     * @param request   HTTP 請求物件，用於提取 Queue 名稱。
     * @param file      要上傳的檔案。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/queue/file", "/queue/file/**"})
    public ResponseEntity<String> uploadFileToQueue(HttpServletRequest request, @RequestParam("file") MultipartFile file) throws JCSMPException, IOException {
        String queueName = getQueueName(request);
        solaceService.sendFile(queueName, SolaceService.DestinationType.QUEUE, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.ok("檔案已成功發送到 Queue: " + queueName);
    }

    /**
     * 訂閱一個 Topic 以開始接收訊息。
     *
     * @param request   HTTP 請求物件，用於提取 Topic 名稱。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/subscribe/topic", "/subscribe/topic/**"})
    public ResponseEntity<String> subscribeToTopic(HttpServletRequest request) throws JCSMPException {
        String topicName = getTopicName(request);
        solaceService.subscribeToTopic(topicName);
        return ResponseEntity.ok("已成功訂閱 Topic: " + topicName);
    }

    /**
     * 開始監聽一個 Queue 以接收訊息。
     *
     * @param request   HTTP 請求物件，用於提取 Queue 名稱。
     * @return ResponseEntity，包含操作成功或失敗的訊息。
     */
    @PostMapping(value = {"/listen/queue", "/listen/queue/**"})
    public ResponseEntity<String> listenToQueue(HttpServletRequest request) throws JCSMPException {
        String queueName = getQueueName(request);
        solaceService.receiveFromQueue(queueName);
        return ResponseEntity.ok("已開始監聽 Queue: " + queueName);
    }

    /**
     * 獲取從指定 Queue 監聽到的訊息。
     * <p>
     * 此端點會返回自上次調用以來在該 Queue 上累積的所有訊息，
     * 包括文字訊息和檔案接收成功的通知。
     * </p>
     * @param request   HTTP 請求物件，用於提取 Queue 名稱。
     * @return ResponseEntity 包含一個訊息字串列表。
     */
    @GetMapping(value = {"/messages/queue", "/messages/queue/**"})
    public ResponseEntity<List<String>> getQueueMessages(HttpServletRequest request) {
        String queueName = getQueueName(request);
        List<String> messages = solaceService.getAndClearQueueMessages(queueName);
        return ResponseEntity.ok(messages);
    }

    /**
     * 獲取從指定 Topic 訂閱到的訊息。
     * <p>
     * 此端點會返回自上次調用以來在該 Topic 上累積的所有訊息，
     * 包括文字訊息和檔案接收成功的通知。
     * </p>
     * @param request   HTTP 請求物件，用於提取 Topic 名稱。
     * @return ResponseEntity 包含一個訊息字串列表。
     */
    @GetMapping(value = {"/messages/topic", "/messages/topic/**"})
    public ResponseEntity<List<String>> getTopicMessages(HttpServletRequest request) {
        String topicName = getTopicName(request);
        List<String> messages = solaceService.getAndClearTopicMessages(topicName);
        return ResponseEntity.ok(messages);
    }
}
