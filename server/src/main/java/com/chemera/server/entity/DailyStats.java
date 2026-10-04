package com.chemera.server.entity;

import lombok.Data;
import java.time.LocalDate;

@Data
public class DailyStats {
    private LocalDate day;
    private Integer dau;
    private Integer newUsers;
    private Integer reactions;
    private Integer booms;
    private Integer trades;
}
