package com.hnp.filemanagement.document;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Document(indexName = "resumes", createIndex = false)
@Data
public class FileContent {

    @Id
    @Field(type = FieldType.Keyword, name = "id")
    private String id;


    @Field(type = FieldType.Text, name = "content")
    private String content;

    @Field(name = "file")
    private FileContentDetails fileContentDetails;

    @Field(name = "path")
    private FilePath filePath;


}
