package com.ebiz.wsb.global.dto;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class BaseResponse {
    protected String message;
    private Object data;
}
