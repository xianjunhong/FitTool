package com.fittool.controller;

import com.fittool.domain.Point;
import com.fittool.dto.request.PreviewRequestDto;
import com.fittool.dto.response.PreviewResponseDto;
import com.fittool.service.FitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;


@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FitController {

    @Autowired
    private FitService fitService;


    @PostMapping("/preview")
    public PreviewResponseDto preview(@RequestBody PreviewRequestDto request) {

        return fitService.preview(request);
    }


    @PostMapping("/generate-fit")
    public ResponseEntity<byte[]> generate(@RequestBody PreviewRequestDto request) throws IOException {

        List<Point> points = request.getPoints();

        for (Point point : points) {
            System.out.println(point.getLat() + " " + point.getLng());
        }
        System.out.println("-----------------");




  
        byte[] fitBytes = fitService.generate(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=run" + ".fit");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/vnd.ant.fit"))
                .body(fitBytes);
    }
}