package tw.zipe.solace.service;

import com.solacesystems.jcsmp.JCSMPSession;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import tw.zipe.solace.config.SolaceProperties;

import java.time.Duration;

/**
 * Solace JCSMP Session 連線池。
 * <p>
 * 使用 Apache Commons Pool2 來管理 {@link JCSMPSession} 物件，
 * 旨在減少建立和關閉 Session 的開銷，提高資源利用效率。
 * Session 的建立由 {@link SolaceSessionFactory} 負責。
 * </p>
 */
public class SolaceConnectionPool {

    private final GenericObjectPool<JCSMPSession> pool;

    /**
     * 建構一個新的 SolaceConnectionPool。
     *
     * @param factory    用於建立和管理 {@link JCSMPSession} 的工廠 ({@link SolaceSessionFactory})。
     * @param poolConfig 連線池的組態設定 ({@link SolaceProperties.Pool})。
     */
    public SolaceConnectionPool(SolaceSessionFactory factory, SolaceProperties.Pool poolConfig) {
        GenericObjectPoolConfig<JCSMPSession> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolConfig.getMaxTotal());
        config.setMinIdle(poolConfig.getMinIdle());
        config.setMaxWait(Duration.ofMillis(poolConfig.getMaxWaitMillis()));
        config.setTestWhileIdle(poolConfig.isTestWhileIdle());
        config.setTimeBetweenEvictionRuns(Duration.ofMillis(poolConfig.getTimeBetweenEvictionRunsMillis()));
        this.pool = new GenericObjectPool<>(factory, config);
        this.pool.setTestOnBorrow(poolConfig.isTestOnBorrow());
    }

    /**
     * 從連線池中借用一個 JCSMP Session。
     *
     * @return 一個可用的 {@link JCSMPSession}。
     * @throws Exception 如果無法從池中取得 Session。
     */
    public JCSMPSession getSession() throws Exception {
        return pool.borrowObject();
    }

    /**
     * 將一個 JCSMP Session 歸還到連線池中。
     *
     * @param session 要歸還的 {@link JCSMPSession}。
     */
    public void returnSession(JCSMPSession session) {
        if (session != null) {
            pool.returnObject(session);
        }
    }

    /**
     * 使一個 JCSMP Session 失效，並從連線池中移除。
     * <p>
     * 當偵測到 Session 無法使用時 (例如，連線中斷)，應呼叫此方法。
     * </p>
     *
     * @param session 要使其失效的 {@link JCSMPSession}。
     */
    public void invalidateSession(JCSMPSession session) {
        if (session != null) {
            try {
                pool.invalidateObject(session);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * 關閉連線池並釋放所有資源。
     */
    public void close() {
        pool.close();
    }
}
