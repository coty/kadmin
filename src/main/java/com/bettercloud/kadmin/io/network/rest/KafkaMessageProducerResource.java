package com.bettercloud.kadmin.io.network.rest;

import ch.qos.logback.classic.Level;
import com.bettercloud.kadmin.api.kafka.JsonToAvroConverter;
import com.bettercloud.kadmin.api.kafka.KafkaProviderService;
import com.bettercloud.kadmin.api.models.KafkaProduceMessageMeta;
import com.bettercloud.kadmin.io.network.dto.ResponseUtil;
import com.bettercloud.messaging.kafka.produce.ProducerService;
import com.bettercloud.util.LoggerUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.avro.AvroTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Optional;

/**
 * Created by davidesposito on 7/1/16.
 */
@RestController
@RequestMapping("/kafka")
public class KafkaMessageProducerResource {

    private static final Logger LOGGER = LoggerUtils.get(KafkaMessageProducerResource.class, Level.TRACE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaProviderService providerService;
    private final JsonToAvroConverter jtaConverter;

    @Autowired
    public KafkaMessageProducerResource(KafkaProviderService providerService,
                                        JsonToAvroConverter jtaConverter) {
        this.providerService = providerService;
        this.jtaConverter = jtaConverter;
    }

    @RequestMapping(
            path = "/publish",
            method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ProducerResponse> publish(@RequestBody KafkaProduceRequestModel requestModel,
                                                 @RequestParam("count") Optional<Integer> oCount) {
        LOGGER.trace("/publish Received {}", requestModel);
        if (requestModel.getRawMessage().getNodeType().equals(JsonNodeType.STRING)) {
            try {
                requestModel.setRawMessage(MAPPER.readTree(requestModel.getRawMessage().asText()));
            } catch (IOException e) {
                return ResponseUtil.error(e);
            }
        }
        LOGGER.trace("/publish Processing: {}", requestModel);
        KafkaProduceMessageMeta meta = requestModel.getMeta();
        Object message;
        try {
            message = jtaConverter.convert(requestModel.getRawMessage().toString(), meta.getRawSchema());
        } catch (AvroTypeException e) {
            return ResponseUtil.error(e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        LOGGER.trace("/publish Avrified: {}", message);
        ProducerService<String, Object> ps = providerService.producerService(meta.getKafkaUrl(), meta.getSchemaRegistryUrl());
        LOGGER.trace("/publish Producer not null {}", ps != null);
        boolean sendMessage = message != null && ps != null;
        LOGGER.trace("/publish Send message {}", sendMessage);
        ProducerResponse res = ProducerResponse.builder()
                .sent(sendMessage)
                .count(0)
                .success(false)
                .duration(-1)
                .rate(-1)
                .build();
        if (sendMessage) {
            res = sendMessage(ps, meta.getTopic(), message, oCount.orElse(1));
        }
        LOGGER.debug("/publish Produced: {}", res);
        return ResponseEntity.ok(res);
    }

    private <PayloadT> ProducerResponse sendMessage(ProducerService<String, PayloadT> ps, String topic,
                                                    PayloadT payload, int count) {
        LOGGER.trace("/publish Sending message (x{}) on {}", count, topic);
        int success = 0;
        long duration;
        double rate;
        long startTime = System.currentTimeMillis();
        for (int i=0;i<count;i++) {
            try {
                ps.send(topic, payload);
                success++;
            } catch (Exception e) {
                LOGGER.error("There was an error", e);
            }
        }
        LOGGER.trace("/publish Done sending ({}/{})", success, count);
        duration = System.currentTimeMillis() - startTime;
        rate = count * 1000.0 / duration;
        return ProducerResponse.builder()
                .count(success)
                .duration(duration)
                .rate(rate)
                .sent(true)
                .success(success > 0 && success == count)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProducerResponse {
        private int count;
        private long duration;
        private double rate;
        private boolean success;
        private boolean sent;
    }
}
