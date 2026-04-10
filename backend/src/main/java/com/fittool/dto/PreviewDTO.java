package com.fittool.dto;

import com.fittool.bo.CoordinateBO;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class PreviewDTO {

    private Instant startDate;
    private List<CoordinateBO> coordinateBOS;
    private Integer paceSecondsPerKm;
    private Integer hrRest;
    private Integer hrMax;
    private Integer lapCount;
    private Double weightKg;

}