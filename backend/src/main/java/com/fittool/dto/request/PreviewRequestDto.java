package com.fittool.dto.request;

import com.fittool.domain.Point;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class PreviewRequestDto {

    private Instant startDate;
    private List<Point> points;
    private Integer paceSecondsPerKm;
    private Integer hrRest;
    private Integer hrMax;
    private Integer lapCount;
    private Double weightKg;

}