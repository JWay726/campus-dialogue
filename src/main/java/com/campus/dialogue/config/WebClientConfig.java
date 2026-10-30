package com.campus.dialogue.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * 向量化通道。必须设超时：校园网 DNS 偶发解析超时，没有超时会一直挂住整条问答链路。
     * 注意语音/推理类模型接口的对话客户端由 {@code LlmClient} 自行构建（需要主备双通道）。
     */
    @Bean
    public WebClient embeddingWebClient(AppProperties props) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(60));
        return WebClient.builder()
                .baseUrl(props.getEmbedding().getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }

    /** 微信开放平台 API 客户端（个人中心绑定微信）。 */
    @Bean
    public WebClient wechatWebClient() {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(20));
        return WebClient.builder()
                .baseUrl("https://api.weixin.qq.com")
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
    }
}
