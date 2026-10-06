package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackageClasses = TestApplication.class, annotationClass = Mapper.class)
class TestApplication {
}
