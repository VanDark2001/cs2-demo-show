package com.csdemo.controller;

import com.csdemo.service.DemoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** HTTP 适配层：只负责参数绑定、路由和统一错误响应。 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:5173")
public class DemoController {
  private final DemoService demoService;

  public DemoController(DemoService demoService) { this.demoService=demoService; }

  @GetMapping("/matches") public List<Map<String,Object>> matches() throws SQLException { return demoService.matches(); }
  @GetMapping("/matches/{id}") public Map<String,Object> detail(@PathVariable long id) throws SQLException { return demoService.detail(id); }
  @GetMapping("/matches/{id}/analytics") public Map<String,Object> analytics(@PathVariable long id) throws SQLException { return demoService.analytics(id); }
  @GetMapping("/players/{steamid}/weapons") public List<Map<String,Object>> playerWeapons(@PathVariable String steamid) throws SQLException { return demoService.playerWeapons(steamid); }
  @GetMapping("/players/{steamid}/stats") public Map<String,Object> playerStats(@PathVariable String steamid) throws SQLException { return demoService.playerStats(steamid); }
  @GetMapping(value="/avatars/{steamid}",produces=MediaType.IMAGE_JPEG_VALUE) public byte[] avatar(@PathVariable String steamid) throws IOException { return demoService.avatar(steamid); }
  @GetMapping("/health/db") public Map<String,Object> health() { return demoService.health(); }
  @PostMapping(value="/demos",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<String,Object> upload(@RequestPart("file") MultipartFile file,@RequestParam(value="recordedAt",required=false) Long recordedAt) throws IOException,InterruptedException,SQLException { return demoService.upload(file,recordedAt); }

  @ExceptionHandler({IllegalStateException.class,SQLException.class,IOException.class})
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public Map<String,Object> error(Exception exception) {
    String message=Optional.ofNullable(exception.getMessage()).filter(value->!value.isBlank()).orElse(exception.getClass().getSimpleName());
    return Map.of("status","error","message",message);
  }
}
