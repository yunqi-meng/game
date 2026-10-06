package com.chemera.server.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 激励视频工单：签发→回调→结算的一次凭证，表 ad_ticket。 */
@Data
public class AdTicket {
    private Long id;
    /** 服务端签发的随机串，作为 SDK extra 原样回传；回调靠它找回这次观看。 */
    private String ticket;
    private Long userId;
    /** 广告位（app_config.ad.slots[].kind）。 */
    private String kind;
    /** 签发时定格的奖励类型与数量，运营中途改配置不会追溯影响。 */
    private String reward;
    private Integer amount;
    private Integer points;
    /** issued/rewarded/settled/expired/void */
    private String status;
    /** 广告网络交易号，唯一索引＝重复回调只算一次。 */
    private String transId;
    private String spaceId;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime rewardedAt;
}
