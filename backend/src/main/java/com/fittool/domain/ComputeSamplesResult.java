package com.fittool.domain;

import com.fittool.dto.response.SampleDto;

import lombok.Data;
import java.util.List;

@Data
public class ComputeSamplesResult {
    private List<SampleDto> samples;
    private Double totalDurationSec;
    private Double avgRunningCadence;
    private Double avgStrideLength;
    private Integer avgPower;
    private Double totalAscent;
    private Double totalDescent;
}