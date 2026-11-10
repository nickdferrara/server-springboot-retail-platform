package com.retailplatform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

@Modulithic(
    systemName = "Retail Operations Platform",
    sharedModules = ["shared", "platform"],
)
@SpringBootApplication
class RetailApplication

fun main(args: Array<String>) {
    runApplication<RetailApplication>(*args)
}
