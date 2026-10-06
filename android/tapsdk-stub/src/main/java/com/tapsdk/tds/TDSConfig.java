package com.tapsdk.tds;

/** 编译期桩（非真实 SDK）。 */
public class TDSConfig {
    public String clientId, clientToken;

    /** provider 里那三参构造按文档写：(client_id, client_token, 预留)。 */
    public void initClientId(String clientId, String clientToken, String reserved) {
        this.clientId = clientId;
        this.clientToken = clientToken;
    }
}
