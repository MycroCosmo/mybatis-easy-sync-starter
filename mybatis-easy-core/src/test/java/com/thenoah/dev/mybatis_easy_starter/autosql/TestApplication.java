package com.thenoah.dev.mybatis_easy_starter.autosql;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan(basePackageClasses = TestApplication.class)
class TestApplication {
}
