package com.miaocang;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 喵藏 MiaoCang —— 内容引力波整理系统
 * 把用户投入的每一条内容（网页链接 / Word / Excel / 文本）像引力波一样
 * 自动吸引到合适的书籍类型，并整理进拥有两级目录的书里，形成个人 Wiki。
 */
@SpringBootApplication
@EnableScheduling
public class MiaoCangApplication {
    public static void main(String[] args) {
        SpringApplication.run(MiaoCangApplication.class, args);
    }
}
