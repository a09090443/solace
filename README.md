# Solace Spring Boot 整合範例

本專案是一個 Spring Boot 應用程式，旨在展示如何與 Solace PubSub+ 事件代理器 (Event Broker) 進行整合。它提供了一套 RESTful API，讓開發者可以方便地在 Solace 的 Topic 和 Queue 上發布與訂閱訊息（包含文字與檔案）。

## 功能特性

- 發布文字訊息到動態指定的 Topic 或 Queue。
- 上傳並發布檔案到動態指定的 Topic 或 Queue。
- 訂閱指定的 Topic 以接收訊息。
- 監聽指定的 Queue 以接收訊息。
- **連線池管理**：為生產者和消費者提供高效能的 Solace 連線池，優化資源利用。
- **自動重連與訂閱恢復**：內建強健的連線重連機制，當 Solace 連線中斷時，應用程式會自動嘗試重新連線，並恢復所有 Topic 訂閱和 Queue 監聽，確保訊息處理的連續性。
- **支援預設值**：當 API 請求未提供 Topic 或 Queue 名稱時，會自動使用設定檔中的預設值。
- 獲取已訂閱 Topic 和已監聽 Queue 中的緩存訊息。
- 附帶 Postman 集合，方便進行 API 測試。

## 環境要求

在執行此應用程式之前，請確保您已安裝並設定好以下環境：

- **Java 17** 或更新版本。
- **Apache Maven**。
- **一個運行中的 Solace PubSub+ Event Broker**。您可以使用 Docker 快速建立一個，請參考 [Solace PubSub+ Docker 指南](https://solace.com/software/getting-started/)。

## 應用程式設定

應用程式的設定檔位於 `src/main/resources/application.properties`。

### 完整設定範例

以下是一個較為完整的設定範例，包含了基本連線、安全連線 (SSL/TLS) 以及應用程式的自訂設定。

```properties
# --- Spring Boot Web 伺服器設定 ---
server.port=9090

# --- Solace 基本連線設定 ---
# 使用 smfs:// 或 tcps:// 來啟用安全連線
solace.jms.host=smfs://localhost:55443
solace.jms.msg-vpn=default
solace.jms.client-username=default
solace.jms.client-password=default

# --- 安全連線 (SSL/TLS) 設定 ---
# (可選) 自訂信任儲存庫 (TrustStore) 的 JKS 檔案路徑。
# 當 Broker 使用自簽章憑證或私有 CA 簽發的憑證時，需要此設定。
solace.jms.ssl.trust-store=D:/path/to/your/truststore.jks
# (可選) 信任儲存庫的密碼。
solace.jms.ssl.trust-store-password=changeit
# (不安全，僅限開發) 是否關閉伺服器憑證驗證。
# 設定為 false 表示信任所有伺服器憑證，請勿在生產環境中使用。
solace.jms.ssl.validate-certificate=false

# --- 應用程式自訂設定 ---
# 用於儲存從 Solace 接收到的檔案的目錄。請修改為您本機的有效目錄。
solace.received.files.directory=D:/temp

# --- 預設目的地設定 ---
# 當 API 請求未提供 Topic 或 Queue 名稱時，使用的預設值。
solace.default.topic=v1/app/default/topic
solace.default.queue=q-app-default-queue
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
