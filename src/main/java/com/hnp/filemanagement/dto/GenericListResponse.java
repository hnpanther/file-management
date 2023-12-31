package com.hnp.filemanagement.dto;

import lombok.Data;

import java.util.List;

@Data
public class GenericListResponse {

    public List<GenericResponse> results;



    public static class GenericResponse {
        public int id;
        public String text;
        public GenericResponse(int id, String text) {
            this.id = id;
            this.text = text;
        }
    }
}
