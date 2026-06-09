package com.hmdp.config;



import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public Queue seckillQueue() {
        return new Queue("seckillQueue");
    }

    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange("seckillExchange");
    }


    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue()).to(fanoutExchange());
    }

}
