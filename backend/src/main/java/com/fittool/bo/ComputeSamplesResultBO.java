package com.fittool.bo;

import lombok.Data;
import java.util.List;

@Data
public class ComputeSamplesResultBO {
    private List<SampleBO> samples;
    private Double totalDurationSec;
    private Double avgRunningCadence;
    private Double avgStrideLength;
    private Integer avgPower;
    private Double totalAscent;
    private Double totalDescent;
}