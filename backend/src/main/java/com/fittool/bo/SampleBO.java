package com.fittool.bo;

import lombok.Data;

@Data
public class SampleBO {
    private Double timeSec;         // 累计时间（秒
    private Double distance;        // 累计距离（米
    private Double speed;           // 当前速度（m/s
    private Integer heartRate;          // 心率（bpm
    private Double lat;
    private Double lng;
    private Double runningCadence;     // 步频 （步/s
    private Double strideLength;    // 步幅(m/步
    private Integer power;              // 功率
    private Double altitude;        // 海拔
}