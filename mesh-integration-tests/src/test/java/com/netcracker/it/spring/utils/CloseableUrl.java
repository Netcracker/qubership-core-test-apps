package com.netcracker.it.spring.utils;

import java.net.URL;

public interface CloseableUrl extends AutoCloseable {
    URL getUrl();
}
