package com.fittool.controller;

import com.fittool.bo.CoordinateBO;
import com.fittool.dto.PreviewDTO;
import com.fittool.vo.PreviewVO;
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
    public PreviewVO preview(@RequestBody PreviewDTO request) {

        return fitService.preview(request);
    }


    @PostMapping("/generate-fit")
    public ResponseEntity<byte[]> generate(@RequestBody PreviewDTO request) throws IOException {

        List<CoordinateBO> coordinateBOS = request.getCoordinateBOS();

        for (CoordinateBO coordinateBO : coordinateBOS) {
            System.out.println(coordinateBO.getLat() + " " + coordinateBO.getLng());
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