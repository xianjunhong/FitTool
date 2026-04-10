package com.fittool.vo;

import com.fittool.bo.SampleBO;
import lombok.Data;

import java.util.List;

@Data
public class PreviewVO {


    private List<SampleBO> samples;
    private Double totalDistanceMeters;
    private Double totalDurationSec;
    private Integer calories;

}