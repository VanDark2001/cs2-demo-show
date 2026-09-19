package com.csdemo.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 统一封装 MySQL 连接参数，避免业务层重复读取环境变量。 */
@Repository
public class DatabaseRepository {
  private final String url;
  private final String user;
  private final String password;
  private final String host;
  private final String database;

  public DatabaseRepository(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String user,
      @Value("${spring.datasource.password:}") String password,
      @Value("${MYSQL_HOST:127.0.0.1}") String host,
      @Value("${MYSQL_DATABASE:csdemo}") String database) {
    this.url=url;
    this.user=user;
    this.password=password;
    this.host=host;
    this.database=database;
  }

  public Connection connection() throws SQLException { return DriverManager.getConnection(url,user,password); }
  public String user() { return user; }
  public String password() { return password; }
  public String host() { return host; }
  public String database() { return database; }
}
