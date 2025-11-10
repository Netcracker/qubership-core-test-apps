package com.netcracker.it.spring.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyValuePair<K, V> {
    private K key;
    private V value;
}
