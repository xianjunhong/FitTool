package com.fittool.service;

import com.fittool.domain.ComputeSamplesResult;
import com.fittool.domain.DistanceSeries;
import com.fittool.domain.Point;
import com.fittool.dto.response.SampleDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class FitUtils {

    public static int toSemicircles(double deg) {
        return (int) Math.round((deg * 2147483648d) / 180d);
    }

    public static float haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (float) (r * c);
    }


    public static double simulateCadence(double speed) {
        Integer baseCadence = 170;
        double speedEffect = (speed - 2.5) * 8;
        double noise = (Math.random() - 0.5) * 6;
        double cadence = baseCadence + speedEffect + noise;
        return Math.max(150, Math.min(200, Math.round(cadence)));
    }



    public static List<Point> buildAllPoints(List<Point> basePoints, Integer laps) {
        List<Point> allPoints = new ArrayList<>();
        for (int i = 0; i < laps; i++) {
            allPoints.addAll(basePoints);
        }
        return allPoints;
    }


    // 计算累计距离数组和总距离�?
    public static DistanceSeries buildDistanceSeries(List<Point> allPoints) {
        List<Double> distances = new ArrayList<>(Arrays.asList(0D));
        Double totalDist = 0D;

        for (int i = 1; i < allPoints.size(); i++) {
            float segment = haversineDistance(
                    allPoints.get(i - 1).getLat(), allPoints.get(i - 1).getLng(),
                    allPoints.get(i).getLat(), allPoints.get(i).getLng()
            );
            totalDist += segment;
            distances.add(totalDist);
        }

        DistanceSeries distanceSeries = new DistanceSeries();
        distanceSeries.setDistances(distances);
        distanceSeries.setTotalDist(totalDist);
        return distanceSeries;
    }


    public static Integer estimateCalories(Double weightKg, Double totalDistMeters) {
        return Math.toIntExact(Math.round(weightKg * (totalDistMeters / 1000) * 1.036));
    }

    public static ComputeSamplesResult computeSamples(List<Point> allPoints,
                                      List<Double> distances,
                                      Double totalDist,
                                      Integer paceSecondsPerKm,
                                      Integer hrRest,
                                      Integer hrMax,
                                      Double weightKg,
                                      List<Double> altitudes
                                      ) {


        int n = allPoints.size();
//        总距离km
        Double totalDistanceKm = totalDist / 1000;
//        总时间秒
        Long targetDurationSec = (long) (totalDistanceKm * paceSecondsPerKm);
//平均速度 m/s
        Double avgSpeedTarget = totalDist / targetDurationSec;


//        基础速度浮动系数�?.98~1.04），再加一点随机�?
        double baseSpeedFactor = 0.98 + Math.random() * 0.06;
        double phase1 = Math.random() * Math.PI * 2;
        double phase2 = Math.random() * Math.PI * 2;
        double baseAlt = 50 + Math.random() * 30;

//        瞬时速度
        double[] instSpeedRaw = new double[n];
        int[] hrValues = new int[n];

        double currentHr = hrRest;

        // 1️⃣ 模拟瞬时速度和心�?
        for (int i = 0; i < n; i++) {
//            进行到哪一部分�?
            double frac = distances.get(i) / totalDist;
//模拟跑步中长期的速度起伏
            double longWave = 0.04 * Math.sin(frac * Math.PI * 2 + phase1);
//            周期更短，波峰更密集 �?快速小幅波动，±2% 的速度波动
            double shortWave = 0.02 * Math.sin(frac * Math.PI * 6 + phase2);
//            每个轨迹点的瞬时速度（m/s�?
            double speedRaw = avgSpeedTarget * baseSpeedFactor * (1 + longWave + shortWave);
            instSpeedRaw[i] = speedRaw;

//            当前跑步比目标快还是慢，瞬时速度转成了一个百分比努力强度，[0,1]，用于后续心率计�?
            double effort = Math.min(1.0, Math.max(0.0, speedRaw / (avgSpeedTarget == 0 ? 1e-6 : avgSpeedTarget)));

//            基础运动强度，最终用来计算心�?
            double intensityBase;
            if (frac < 0.1) {
//                热身，心率逐渐上升
                double f = frac / 0.1;
                intensityBase = 0.4 + 0.4 * f;
            } else if (frac < 0.8) {
//                中段恒定强度，有小波�?
                double f = (frac - 0.1) / 0.7;
                intensityBase = 0.8 + 0.05 * Math.sin(f * Math.PI * 2);
            } else {
//                末段冲刺，强度逐渐上升
                double f = (frac - 0.8) / 0.2;
                intensityBase = 0.85 + 0.1 * f;
            }

            double intensity = Math.min(1.0, Math.max(0.0, 0.7 * intensityBase + 0.3 * effort));

            double hrTarget = hrRest + (hrMax - hrRest) * intensity;

            hrValues[i] = (int)hrTarget;
        }

        // 2️⃣ 计算每段原始持续时间
        double[] segDurationsRaw = new double[Math.max(0, n)];
        double rawDuration = 0;
        for (int i = 1; i < n; i++) {
//            每段的距�?
            double ds = distances.get(i) - distances.get(i - 1);
            double v = instSpeedRaw[i] > 0 ? instSpeedRaw[i] : avgSpeedTarget;
            double dt = ds / v;
            segDurationsRaw[i] = dt;
            rawDuration += dt;
        }

        double scale = rawDuration > 0 ? targetDurationSec / rawDuration : 1;

        // 3️⃣ 构建样本列表
        List<SampleDto> samples = new ArrayList<>();
        double t = 0;

        for (int i = 0; i < n; i++) {
            double speed = instSpeedRaw[i] / scale;
            double frac = distances.get(i) / totalDist;

//            步频（步/分钟�?
            double runningCadence = simulateCadence(speed);
//            步幅（m/步）
            double strideLength = runningCadence > 0 ? speed / (runningCadence / 60.0) : 1.0;
            int power = (int) Math.round(weightKg * speed * 1.04);
            boolean hasRealAltitude = altitudes != null
                    && altitudes.size() == n
                    && altitudes.get(i) != null;
            double altitude = hasRealAltitude
                    ? altitudes.get(i)
                    : baseAlt + 2 * Math.sin(frac * Math.PI * 4) + (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.8;
            t += segDurationsRaw[i] * scale;

            SampleDto sample = new SampleDto();
            sample.setTimeSec(t);
            sample.setDistance(distances.get(i));
            sample.setSpeed(speed);
            sample.setHeartRate(hrValues[i]);
            sample.setLat(allPoints.get(i).getLat());
            sample.setLng(allPoints.get(i).getLng());
            sample.setRunningCadence(runningCadence);
            sample.setStrideLength(strideLength);
            sample.setPower(power);
            sample.setAltitude(altitude);

            samples.add(sample);


        }

        // 4️⃣ 汇总
//        总时
        Double totalDurationSec = samples.isEmpty() ? targetDurationSec :  samples.get(samples.size() - 1).getTimeSec();
//平均步频
        double avgRunningCadence = Math.round(samples.stream().mapToDouble(SampleDto::getRunningCadence).average().orElse(0));
        double avgStrideLength = samples.stream().mapToDouble(SampleDto::getStrideLength).average().orElse(0);
        int avgPower = (int) Math.round(samples.stream().mapToInt(SampleDto::getPower).average().orElse(0));

        double totalAscent = 0;
        double totalDescent = 0;
        for (int i = 1; i < samples.size(); i++) {
            double diff = samples.get(i).getAltitude() - samples.get(i - 1).getAltitude();
            if (diff > 0) totalAscent += diff;
            else totalDescent += Math.abs(diff);
        }

        ComputeSamplesResult result = new ComputeSamplesResult();
        result.setSamples(samples);
        result.setTotalDurationSec(totalDurationSec);
        result.setAvgRunningCadence(avgRunningCadence);
        result.setAvgStrideLength(avgStrideLength);
        result.setAvgPower(avgPower);
        result.setTotalAscent(totalAscent);
        result.setTotalDescent(totalDescent);

        return result;




    }



}
