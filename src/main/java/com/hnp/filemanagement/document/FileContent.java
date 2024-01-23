package com.hnp.filemanagement.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "resumes")
@Data
public class FileContent {

    @Id
    @Field(type = FieldType.Text, name = "file.checksum")
    private String checksum;

    @Field(type = FieldType.Text, name = "file.url")
    private String fileUrl;

    @Field(type = FieldType.Text, name = "file.content_type")
    private String fileType;

    @Field(type = FieldType.Text, name = "content")
    private String content;

}
