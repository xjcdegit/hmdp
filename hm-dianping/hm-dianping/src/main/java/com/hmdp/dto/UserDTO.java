package com.hmdp.dto;

import lombok.Data;

@Data
public class UserDTO {
    //减少保存了一些敏感信息
    //作用：
    //1.减少Session压力，减少不必要数据的保存
    //2.避免了敏感信息的泄露，保证了信息安全
    private Long id;
    private String nickName;
    private String icon;
}
