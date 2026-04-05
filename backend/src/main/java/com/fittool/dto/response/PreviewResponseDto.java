package com.fittool.dto.response;

import lombok.Data;

import java.util.List;

@Data
public class PreviewResponseDto {


    private List<SampleDto> samples;
    private Double totalDistanceMeters;
    private Double totalDurationSec;
    private Integer calories;



}