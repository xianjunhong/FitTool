package com.fittool.bo;

import lombok.Data;

import java.util.List;
//计算累计距离数组和总距离
@Data
public class DistanceSeriesBO {
    private List<Double> distances;
    private Double totalDist;


}