package com.microsoft.mcp.sample.server;

import com.microsoft.mcp.sample.server.service.MigrationService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(McpServerApplication.class, args);
	}

	@Bean
	public ToolCallbackProvider migrationTools(MigrationService migration){
		return MethodToolCallbackProvider.builder().toolObjects(migration).build();
	}
}
