# Solace Spring Boot 整合範例

本專案是一個 Spring Boot 應用程式，旨在展示如何與 Solace PubSub+ 事件代理器 (Event Broker) 進行整合。它提供了一套 RESTful API，讓開發者可以方便地在 Solace 的 Topic 和 Queue 上發布與訂閱訊息（包含文字與檔案）。

## 功能特性

- 發布文字訊息到動態指定的 Topic 或 Queue。
- 上傳並發布檔案到動態指定的 Topic 或 Queue。
- 訂閱指定的 Topic 以接收訊息。
- 監聽指定的 Queue 以接收訊息。
- 獲取已訂閱 Topic 和已監聽 Queue 中的緩存訊息。
- 附帶 Postman 集合，方便進行 API 測試。

## 環境要求

在執行此應用程式之前，請確保您已安裝並設定好以下環境：

- **Java 17** 或更新版本。
- **Apache Maven**。
- **一個運行中的 Solace PubSub+ Event Broker**。您可以使用 Docker 快速建立一個，請參考 [Solace PubSub+ Docker 指南](https://solace.com/software/getting-started/)。

## 應用程式設定

應用程式的設定檔位於 `src/main/resources/application.properties`。

```properties
# Solace 連線資訊
solace.jms.host=tcp://localhost:55554
solace.jms.msg-vpn=default
solace.jms.client-username=default
solace.jms.client-password=default

# 伺服器埠號設定
server.port=9090

# 用於儲存從 Solace 接收到的檔案的目錄
# 請將此路徑修改為您本機的有效目錄。
solace.received.files.directory=D:/temp
```

請務必根據您的環境更新這些設定，特別是 `solace.jms.host` 和 `solace.received.files.directory`。

## 如何執行

1.  **克隆專案原始碼：**
    ```bash
    git clone <your-repository-url>
    cd solace
    ```

2.  **使用 Maven 建置專案：**
    ```bash
    ./mvnw clean install
    ```

3.  **執行應用程式：**
    ```bash
    ./mvnw spring-boot:run
    ```
    應用程式將會啟動並監聽 `application.properties` 中設定的埠號（預設為 `9090`）。

## API 端點

所有 API 端點的路徑都以 `/api/solace` 開頭。路徑中的 `**` 代表動態的 Topic 或 Queue 名稱（例如：`/api/solace/topic/user/created`）。

| 方法   | 端點                          | 描述                                      | 請求內容/參數                             |
| :----- | :---------------------------- | :---------------------------------------- | :---------------------------------------- |
| `POST` | `/topic/**`                   | 發布文字訊息到指定的 Topic。              | `String` (例如 `text/plain`)              |
| `POST` | `/queue/**`                   | 發布文字訊息到指定的 Queue。              | `String` (例如 `text/plain`)              |
| `POST` | `/topic/file/**`              | 上傳並發布檔案到指定的 Topic。            | `MultipartFile`，表單欄位名稱為 `file`    |
| `POST` | `/queue/file/**`              | 上傳並發布檔案到指定的 Queue。            | `MultipartFile`，表單欄位名稱為 `file`    |
| `POST` | `/subscribe/topic/**`         | 訂閱一個 Topic 以開始接收訊息。           | (無)                                      |
| `POST` | `/listen/queue/**`            | 監聽一個 Queue 以開始接收訊息。           | (無)                                      |
| `GET`  | `/messages/topic/**`          | 獲取指定 Topic 的所有緩存訊息。           | (無)                                      |
| `GET`  | `/messages/queue/**`          | 獲取指定 Queue 的所有緩存訊息。           | (無)                                      |

---

### 使用範例 (`curl`)

**1. 發布文字訊息到 Topic `sensor/temperature`：**

```bash
curl -X POST http://localhost:9090/api/solace/topic/sensor/temperature \
-H "Content-Type: text/plain" \
-d "25.5 C"
```

**2. 訂閱 Topic `sensor/temperature`：**

```bash
curl -X POST http://localhost:9090/api/solace/subscribe/topic/sensor/temperature
```

**3. 獲取 Topic `sensor/temperature` 的訊息：**

```bash
curl -X GET http://localhost:9090/api/solace/messages/topic/sensor/temperature
```

**4. 發布一個檔案到 Queue `file-processing`：**

```bash
curl -X POST http://localhost:9090/api/solace/queue/file/file-processing \
-F "file=@/path/to/your/file.txt"
```

## Postman 集合

在專案的 `/postman` 目錄下，我們提供了一個 Postman 集合檔案 (`Solace.postman_collection.json`)。您可以將其匯入到您的 Postman 工具中，以便更輕鬆地對所有 API 端點進行測試。