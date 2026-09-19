package com.csdemo.service;

import com.csdemo.repository.DatabaseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo 业务服务，负责比赛缓存、统计计算、头像读取和解析任务调度。
 *
 * <p>统计结果必须以 MySQL {@code csdemo} 为准；队伍身份始终来自
 * {@code match_players.team_number}，不能根据 T/CT 阵营或页面顺序推断。</p>
 */
@Service
public class DemoService {
  private final DatabaseRepository databaseRepository;

  // 进程内去重只防止同一 Demo 被并发解析；数据库指纹负责跨进程、跨重启去重。
  private final Set<String> activeDemoHashes=ConcurrentHashMap.newKeySet();
  private volatile boolean demoHashesPrepared=false;

  // 比赛列表有磁盘快照和内存缓存，单场分析只保存在当前进程内。
  private volatile List<Map<String,Object>> matchesCache;
  private final Map<Long,Map<String,Object>> analyticsCache=new ConcurrentHashMap<>();
  private final AtomicBoolean matchesRefreshRunning=new AtomicBoolean(false);
  private final ObjectMapper objectMapper=new ObjectMapper();
  private final Path matchesSnapshot=Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve(".cache").resolve("matches.json");

  public DemoService(DatabaseRepository databaseRepository) {
    this.databaseRepository=databaseRepository;
  }

  /** 启动时先加载快照以快速响应，再异步用 MySQL 中的真实数据刷新。 */
  @PostConstruct public void initializeMatchesCache(){
    try{Class.forName("com.mysql.cj.jdbc.Driver");}catch(ClassNotFoundException e){throw new IllegalStateException("MySQL JDBC 驱动未安装",e);}
    if(Files.isRegularFile(matchesSnapshot))try{matchesCache=List.copyOf(objectMapper.readValue(matchesSnapshot.toFile(),new TypeReference<List<Map<String,Object>>>(){}));System.out.println("[csdemo] loaded "+matchesCache.size()+" matches from server cache");}catch(Exception e){System.err.println("[csdemo] matches cache ignored: "+e.getMessage());}
    refreshMatchesAsync();
  }

  /** 首次无缓存时同步加载；双重检查避免多个请求重复执行整库查询。 */
  public List<Map<String,Object>> matches() throws SQLException {
    List<Map<String,Object>> cached=matchesCache;if(cached!=null)return cached;
    synchronized(this){cached=matchesCache;if(cached!=null)return cached;matchesCache=loadMatchesFromDatabase();writeMatchesSnapshot(matchesCache);return matchesCache;}
  }
  /**
   * 加载最近比赛，并按“Demo 文件名 + 最后事件 tick”过滤历史重复记录。
   * 去重后的比赛 ID 也是所有跨比赛统计的统一数据集。
   */
  private List<Map<String,Object>> loadMatchesFromDatabase()throws SQLException{
    List<Map<String,Object>> out=new ArrayList<>();Set<String> seenMatches=new HashSet<>();
    try(Connection c=databaseRepository.connection(); PreparedStatement ps=c.prepareStatement("SELECT id,map_name,server_name,COALESCE(recorded_at,imported_at) display_time,recording_time_source,demo_file_path,final_score_t,final_score_ct,official_start_tick,warmup_end_tick,(SELECT COALESCE(MAX(e.tick),0) FROM events e WHERE e.match_id=matches.id) event_end_tick FROM matches ORDER BY recorded_at IS NULL,COALESCE(recorded_at,imported_at) DESC,id DESC LIMIT 200")){
      ResultSet rs=ps.executeQuery();
      while(rs.next()){
        long id=rs.getLong("id");String storedPath=rs.getString("demo_file_path"),fileName;try{fileName=Path.of(storedPath).getFileName().toString();}catch(Exception ignored){fileName=storedPath;}String fingerprint=fileName+"|"+rs.getLong("event_end_tick");if(!seenMatches.add(fingerprint))continue;Map<String,Object> m=new LinkedHashMap<>();
        m.put("id",id);m.put("map",rs.getString("map_name"));m.put("result","已解析");m.put("score",rs.getObject("final_score_ct")==null?"—":rs.getInt("final_score_ct")+" : "+rs.getInt("final_score_t"));m.put("date",rs.getString("display_time"));m.put("dateSource",rs.getString("recording_time_source"));m.put("server",rs.getString("server_name"));m.put("file",rs.getString("demo_file_path"));m.put("status","已完成");m.put("tickRate",64);
        try{m.put("players",loadPlayers(c,id,nullableLong(rs,"official_start_tick"),nullableLong(rs,"warmup_end_tick")));}
        catch(Exception e){System.err.println("[csdemo] match "+id+" stats degraded: "+e.getMessage());m.put("players",loadRoster(c,id));m.put("statsWarning",e.getMessage());}
        out.add(m);
      }
    }
    return List.copyOf(out);
  }
  /** 后台刷新期间保留旧缓存，避免上传完成后的数据库查询阻塞页面。 */
  private void refreshMatchesAsync(){
    if(!matchesRefreshRunning.compareAndSet(false,true))return;
    CompletableFuture.runAsync(()->{try{List<Map<String,Object>> fresh=loadMatchesFromDatabase();matchesCache=fresh;writeMatchesSnapshot(fresh);System.out.println("[csdemo] refreshed "+fresh.size()+" matches in server cache");}catch(Exception e){System.err.println("[csdemo] matches cache refresh failed: "+e.getMessage());}finally{matchesRefreshRunning.set(false);}});
  }
  /** 先写临时文件再原子替换，防止进程中断留下半份 JSON。 */
  private void writeMatchesSnapshot(List<Map<String,Object>> data){
    try{Files.createDirectories(matchesSnapshot.getParent());Path temporary=matchesSnapshot.resolveSibling("matches.json.tmp");objectMapper.writeValue(temporary.toFile(),data);try{Files.move(temporary,matchesSnapshot,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(temporary,matchesSnapshot,StandardCopyOption.REPLACE_EXISTING);}}
    catch(Exception e){System.err.println("[csdemo] matches cache write failed: "+e.getMessage());}
  }
  public Map<String,Object> detail(long id)throws SQLException{return matches().stream().filter(m->((Number)m.get("id")).longValue()==id).findFirst().orElseThrow();}
  public Map<String,Object> analytics(long id)throws SQLException{Map<String,Object> cached=analyticsCache.get(id);if(cached!=null)return cached;try(Connection c=databaseRepository.connection()){Map<String,Object> loaded=loadAnalytics(c,id);analyticsCache.put(id,loaded);return loaded;}}
  public List<Map<String,Object>> playerWeapons(String steamid)throws SQLException{
    if(!steamid.matches("\\d{17}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SteamID 格式不正确");
    List<Long> matchIds=playerMatchIds(steamid);
    if(matchIds.isEmpty())return List.of();
    String normalized=normalizedWeaponSql("k");
    List<Map<String,Object>> out=new ArrayList<>();
    String placeholders=String.join(",",Collections.nCopies(matchIds.size(),"?"));
    try(Connection c=databaseRepository.connection();PreparedStatement ps=c.prepareStatement("SELECT "+normalized+" weapon,COUNT(*) kills,COALESCE(SUM(k.headshot),0) headshots,COUNT(DISTINCT k.match_id) matches FROM kills k JOIN matches m ON m.id=k.match_id JOIN match_players a ON a.match_id=k.match_id AND a.steamid=k.attacker_steamid JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.attacker_steamid=? AND k.match_id IN ("+placeholders+") AND k.attacker_steamid<>k.victim_steamid AND a.team_number<>v.team_number AND k.tick>=COALESCE(m.official_start_tick,m.warmup_end_tick,0) "+validRound("k")+" GROUP BY weapon ORDER BY kills DESC LIMIT 8")){
      ps.setString(1,steamid);for(int i=0;i<matchIds.size();i++)ps.setLong(i+2,matchIds.get(i));ResultSet rs=ps.executeQuery();while(rs.next())out.add(Map.of("weapon",Optional.ofNullable(rs.getString(1)).orElse("unknown"),"kills",rs.getInt(2),"headshots",rs.getInt(3),"matches",rs.getInt(4)));
    }
    return out;
  }
  /** 跨去重比赛汇总玩家的阵营回合和残局尝试；队伍身份始终来自 match_players。 */
  public Map<String,Object> playerStats(String steamid)throws SQLException{
    if(!steamid.matches("\\d{17}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SteamID 格式不正确");
    List<Long> matchIds=playerMatchIds(steamid);int ctRounds=0,ctWins=0,tRounds=0,tWins=0,clutchAttempts=0,clutchWins=0;Map<Integer,int[]> clutchByX=new TreeMap<>();
    try(Connection c=databaseRepository.connection()){
      for(long matchId:matchIds){
        Map<Integer,LinkedHashSet<String>> rosters=new LinkedHashMap<>();int playerTeam=0;
        try(PreparedStatement ps=c.prepareStatement("SELECT steamid,team_number FROM match_players WHERE match_id=?")){ps.setLong(1,matchId);ResultSet rs=ps.executeQuery();while(rs.next()){String sid=rs.getString(1);int team=rs.getInt(2);rosters.computeIfAbsent(team,key->new LinkedHashSet<>()).add(sid);if(steamid.equals(sid))playerTeam=team;}}
        if(playerTeam==0)continue;
        long matchStart=scalarLong(c,"SELECT COALESCE(official_start_tick,warmup_end_tick,0) FROM matches WHERE id=?",matchId);
        List<Map.Entry<Long,String>> roundEnds=new ArrayList<>();
        try(PreparedStatement ps=c.prepareStatement("SELECT tick,JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) winner FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT') AND tick>=? GROUP BY tick,winner ORDER BY tick")){ps.setLong(1,matchId);ps.setLong(2,matchStart);ResultSet rs=ps.executeQuery();while(rs.next())roundEnds.add(Map.entry(rs.getLong(1),rs.getString(2)));}
        if(roundEnds.isEmpty())continue;
        List<Object[]> deaths=new ArrayList<>();
        try(PreparedStatement ps=c.prepareStatement("SELECT tick,victim_steamid FROM kills WHERE match_id=? AND tick BETWEEN ? AND ? ORDER BY tick,id")){ps.setLong(1,matchId);ps.setLong(2,matchStart);ps.setLong(3,roundEnds.get(roundEnds.size()-1).getKey());ResultSet rs=ps.executeQuery();while(rs.next())deaths.add(new Object[]{rs.getLong(1),rs.getString(2)});}
        int deathIndex=0;
        for(int roundIndex=0;roundIndex<roundEnds.size();roundIndex++){
          long roundStart=roundIndex==0?matchStart:roundEnds.get(roundIndex-1).getKey()+1,roundEnd=roundEnds.get(roundIndex).getKey();int roundNumber=roundIndex+1;boolean differsFromFinal=sidesDifferFromFinal(roundNumber,roundEnds.size());int playerSide=differsFromFinal?oppositeSide(playerTeam):playerTeam,winnerSide="T".equalsIgnoreCase(roundEnds.get(roundIndex).getValue())?2:3;
          boolean roundWon=playerSide==winnerSide;if(playerSide==3){ctRounds++;if(roundWon)ctWins++;}else if(playerSide==2){tRounds++;if(roundWon)tWins++;}
          Map<Integer,LinkedHashSet<String>> alive=new LinkedHashMap<>();for(var roster:rosters.entrySet())alive.put(roster.getKey(),new LinkedHashSet<>(roster.getValue()));Integer clutchX=null;
          while(deathIndex<deaths.size()&&((Number)deaths.get(deathIndex)[0]).longValue()<roundStart)deathIndex++;
          int scanIndex=deathIndex;
          while(scanIndex<deaths.size()&&((Number)deaths.get(scanIndex)[0]).longValue()<=roundEnd){String victim=String.valueOf(deaths.get(scanIndex)[1]);for(var team:alive.values())team.remove(victim);LinkedHashSet<String> own=alive.get(playerTeam);if(clutchX==null&&own!=null&&own.size()==1&&own.contains(steamid)){int opponents=0;for(var entry:alive.entrySet())if(entry.getKey()!=playerTeam)opponents+=entry.getValue().size();if(opponents>0)clutchX=opponents;}scanIndex++;}
          deathIndex=scanIndex;
          if(clutchX!=null){clutchAttempts++;int[] bucket=clutchByX.computeIfAbsent(clutchX,key->new int[2]);bucket[0]++;LinkedHashSet<String> own=alive.get(playerTeam);if(roundWon&&own!=null&&own.contains(steamid)){clutchWins++;bucket[1]++;}}
        }
      }
    }
    List<Map<String,Object>> breakdown=new ArrayList<>();for(var entry:clutchByX.entrySet()){int[] values=entry.getValue();breakdown.add(Map.of("x",entry.getKey(),"attempts",values[0],"wins",values[1],"winRate",percentage(values[1],values[0])));}
    Map<String,Object> sides=new LinkedHashMap<>();sides.put("ct",sideStats(ctRounds,ctWins));sides.put("t",sideStats(tRounds,tWins));Map<String,Object> clutches=new LinkedHashMap<>();clutches.put("attempts",clutchAttempts);clutches.put("wins",clutchWins);clutches.put("losses",clutchAttempts-clutchWins);clutches.put("winRate",percentage(clutchWins,clutchAttempts));clutches.put("breakdown",breakdown);
    int rounds=ctRounds+tRounds,wins=ctWins+tWins;Map<String,Object> out=new LinkedHashMap<>();out.put("matches",matchIds.size());out.put("rounds",rounds);out.put("roundWins",wins);out.put("roundWinRate",percentage(wins,rounds));out.put("sides",sides);out.put("clutches",clutches);return out;
  }
  private List<Long> playerMatchIds(String steamid)throws SQLException{return matches().stream().filter(match->((List<?>)match.getOrDefault("players",List.of())).stream().anyMatch(player->player instanceof Map<?,?> row&&steamid.equals(String.valueOf(row.get("steamid"))))).map(match->((Number)match.get("id")).longValue()).toList();}
  private int oppositeSide(int side){return side==2?3:side==3?2:side;}
  private double percentage(int wins,int total){return total==0?0:Math.round(wins*1000.0/total)/10.0;}
  private Map<String,Object> sideStats(int rounds,int wins){Map<String,Object> out=new LinkedHashMap<>();out.put("rounds",rounds);out.put("wins",wins);out.put("losses",rounds-wins);out.put("winRate",percentage(wins,rounds));return out;}
  public byte[] avatar(String steamid)throws IOException{
    if(!steamid.matches("\\d{17}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent(),avatar=root==null?null:root.resolve("avatars").resolve(steamid+".jpg");
    if(avatar==null||!Files.isRegularFile(avatar))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return Files.readAllBytes(avatar);
  }
  public Map<String,Object> health(){try(Connection ignored=databaseRepository.connection()){return Map.of("database","csdemo","status","ok");}catch(SQLException e){return Map.of("database","csdemo","status","unavailable","message",e.getMessage());}}
  /**
   * 暂存上传文件、计算 SHA-256、调用 Python 解析器，并在所有退出路径清理 Demo。
   * 死锁和锁等待属于可恢复错误，最多重试三次；单次解析最长等待十五分钟。
   */
  public Map<String,Object> upload(MultipartFile file,Long recordedAt)throws IOException,InterruptedException,SQLException{
    if(file.isEmpty()||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".dem"))throw new IllegalArgumentException("请上传 .dem 文件");
    Path parserRoot=Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
    if(parserRoot==null)throw new IllegalStateException("无法定位解析器目录");
    Path python=parserRoot.resolve(".venv312/Scripts/python.exe");
    Path script=parserRoot.resolve("demoparser2_mysql.py");
    if(!Files.isRegularFile(python)||!Files.isRegularFile(script))throw new IllegalStateException("Python 解析器不存在："+script);
    Path dir=parserRoot.resolve("uploads");Files.createDirectories(dir);
    Path staged=Files.createTempFile(dir,"demo-upload-",".dem");Files.copy(file.getInputStream(),staged,StandardCopyOption.REPLACE_EXISTING);
    String originalName=Path.of(file.getOriginalFilename()).getFileName().toString();String demoHash=sha256(staged);Long duplicateId=findDuplicateMatch(demoHash,originalName);
    if(duplicateId!=null){Files.deleteIfExists(staged);return Map.of("status","skipped","matchId",duplicateId,"message","数据库已存在同一 Demo，已跳过解析");}
    if(!activeDemoHashes.add(demoHash)){Files.deleteIfExists(staged);return Map.of("status","skipped","message","相同 Demo 正在解析，已跳过重复任务");}
    Path target=dir.resolve(originalName);
    try{
      Files.move(staged,target,StandardCopyOption.REPLACE_EXISTING);
      if(recordedAt!=null&&recordedAt>0)Files.setLastModifiedTime(target,FileTime.fromMillis(recordedAt));
      String output="";boolean parsed=false;
      for(int attempt=1;attempt<=3&&!parsed;attempt++){
      Path log=Files.createTempFile("csdemo-import-",".log");
      try{
        ProcessBuilder pb=new ProcessBuilder(python.toString(),script.toString(),target.toString());
        pb.directory(parserRoot.toFile());pb.redirectErrorStream(true);pb.redirectOutput(log.toFile());
        Map<String,String> env=pb.environment();env.put("MYSQL_HOST",databaseRepository.host());env.put("MYSQL_USER",databaseRepository.user());env.put("MYSQL_PASSWORD",databaseRepository.password());env.put("MYSQL_DATABASE",databaseRepository.database());env.put("DEMO_SHA256",demoHash);
        Process process=pb.start();boolean finished=process.waitFor(15,TimeUnit.MINUTES);
        if(!finished){process.destroyForcibly();throw new IllegalStateException("Demo 解析超时（15 分钟）");}
        output=Files.readString(log,StandardCharsets.UTF_8);
        if(process.exitValue()==0){parsed=true;break;}
        String lower=output.toLowerCase(Locale.ROOT);boolean transientFailure=lower.contains("deadlock")||lower.contains("lock wait")||lower.contains("operationalerror")||lower.contains("duplicate entry");
        if(!transientFailure||attempt==3)throw new IllegalStateException("Demo 解析失败："+output);
        Thread.sleep(attempt*1000L);
      }finally{Files.deleteIfExists(log);}
      }
      analyticsCache.clear();refreshMatchesAsync();return Map.of("status","parsed","fileName",originalName,"demoFingerprint",demoHash,"message","解析完成，服务器端 Demo 已删除","output",output);
    }finally{activeDemoHashes.remove(demoHash);Files.deleteIfExists(staged);Files.deleteIfExists(target);}
  }

  private String sha256(Path path)throws IOException{
    try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[1024*1024];for(int read;(read=input.read(buffer))!=-1;)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}
    catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("系统不支持 SHA-256",e);}
  }

  /** 每个进程只检查一次指纹列，避免每次上传都执行 information_schema 查询或 DDL。 */
  private synchronized void prepareDemoHashes()throws SQLException{
    if(demoHashesPrepared)return;
    try(Connection c=databaseRepository.connection()){
      boolean exists;
      try(PreparedStatement ps=c.prepareStatement("SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='matches' AND COLUMN_NAME='demo_sha256' LIMIT 1")){
        try(ResultSet rs=ps.executeQuery()){exists=rs.next();}
      }
      if(!exists){try(Statement statement=c.createStatement()){statement.executeUpdate("ALTER TABLE matches ADD COLUMN demo_sha256 CHAR(64) NULL");}}
    }
    demoHashesPrepared=true;
  }

  private Long findDuplicateMatch(String hash,String fileName)throws SQLException{
    prepareDemoHashes();try(Connection c=databaseRepository.connection();PreparedStatement ps=c.prepareStatement("SELECT id FROM matches WHERE demo_sha256=? OR SUBSTRING_INDEX(REPLACE(demo_file_path,'\\\\','/'),'/',-1)=? ORDER BY id DESC LIMIT 1")){ps.setString(1,hash);ps.setString(2,fileName);ResultSet rs=ps.executeQuery();return rs.next()?rs.getLong(1):null;}
  }

  /**
   * 计算单场玩家数据。正式边界优先使用 official_start_tick，其次使用热身结束 tick；
   * 结束边界取最后一个有效 round_end，排除热身和赛后退出产生的事件。
   */
  private List<Map<String,Object>> loadPlayers(Connection c,long matchId,Long official,Long warmup)throws SQLException{
    List<Map<String,Object>> players=loadRoster(c,matchId);if(players.isEmpty())return players;
    long start=firstNonZero(official,warmup,scalarLong(c,"SELECT MIN(tick) FROM rounds WHERE match_id=? AND start_type='round_freeze_end'",matchId),scalarLong(c,"SELECT MIN(tick) FROM rounds WHERE match_id=? AND start_type='round_prestart'",matchId),0L);
    long end=scalarLong(c,"SELECT MAX(tick) FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT')",matchId);if(end<=0)end=scalarLong(c,"SELECT MAX(tick) FROM rounds WHERE match_id=? AND start_type='round_officially_ended'",matchId);if(end<=0)end=Long.MAX_VALUE;
    int rounds=scalarInt(c,"SELECT COUNT(DISTINCT tick) FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT') AND tick BETWEEN ? AND ?",matchId,start,end);if(rounds<=0)rounds=scalarInt(c,"SELECT COUNT(DISTINCT tick) FROM rounds WHERE match_id=? AND start_type='round_officially_ended' AND tick BETWEEN ? AND ?",matchId,start,end);if(rounds<=0)rounds=1;
    Map<String,int[]> kills=new HashMap<>();
    try(PreparedStatement ps=c.prepareStatement("SELECT k.attacker_steamid,COUNT(*),COALESCE(SUM(k.headshot),0),SUM(CASE WHEN k.weapon LIKE '%awp%' OR k.weapon LIKE '%ssg%' OR k.weapon LIKE '%scar%' OR k.weapon LIKE '%g3sg1%' THEN 1 ELSE 0 END) FROM kills k JOIN match_players a ON a.match_id=k.match_id AND a.steamid=k.attacker_steamid JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.match_id=? AND k.tick BETWEEN ? AND ? AND a.team_number<>v.team_number AND k.attacker_steamid<>k.victim_steamid "+validRound("k")+" GROUP BY k.attacker_steamid")){ps.setLong(1,matchId);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next())kills.put(rs.getString(1),new int[]{rs.getInt(2),rs.getInt(3),rs.getInt(4)});}
    Map<String,Integer> deaths=countBy(c,"SELECT k.victim_steamid,COUNT(*) FROM kills k WHERE k.match_id=? AND k.tick BETWEEN ? AND ? "+validRound("k")+" GROUP BY k.victim_steamid",matchId,start,end);
    Map<String,Integer> assists=countBy(c,"SELECT s.steamid,COUNT(*) FROM kills k JOIN match_players s ON s.match_id=k.match_id AND s.steamid=JSON_UNQUOTE(JSON_EXTRACT(k.data_json,'$.assister_steamid')) JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.match_id=? AND k.tick BETWEEN ? AND ? AND s.team_number<>v.team_number "+validRound("k")+" GROUP BY s.steamid",matchId,start,end);
    Map<String,Double> damage=effectiveDamage(c,matchId,start,end);try(PreparedStatement ps=c.prepareStatement("SELECT steamid,total_damage FROM match_players WHERE match_id=? AND total_damage IS NOT NULL")){ps.setLong(1,matchId);ResultSet rs=ps.executeQuery();while(rs.next())damage.put(rs.getString(1),rs.getDouble(2));}Map<String,Integer> multi=multiKillRounds(c,matchId,start,end);final int rc=rounds;Map<String,Double> we=loadWe(c,matchId,start,end,rc,players,damage,kills,assists);
    for(Map<String,Object> p:players){String sid=(String)p.get("steamid");int[] k=kills.getOrDefault(sid,new int[3]);int d=deaths.getOrDefault(sid,0),a=assists.getOrDefault(sid,0),mk=multi.getOrDefault(sid,0);double adr=damage.getOrDefault(sid,0.0)/rc,kpr=k[0]/(double)rc,dpr=d/(double)rc,apr=a/(double)rc,survival=Math.max(0,1-dpr),impact=Math.max(0,2.13*kpr+0.42*apr-0.41),multiRate=mk/(double)rc;double rating=0.32*(kpr/0.68)+0.18*(survival/0.65)+0.24*(adr/70.0)+0.10*(apr/0.16)+0.10*(impact/1.10)+0.06*(multiRate/0.20);rating=Math.max(0,Math.min(2.50,rating));p.put("kills",k[0]);p.put("deaths",d);p.put("assists",a);p.put("kda",k[0]+"-"+d+"-"+a);p.put("adr",Math.round(adr*10.0)/10.0);p.put("hs",k[0]==0?0:Math.round(k[1]*1000.0/k[0])/10.0);p.put("aim",k[1]);p.put("rating",Math.round(rating*100.0)/100.0);p.put("we",we.getOrDefault(sid,0.0));p.put("multiKillRounds",mk);p.put("badges",List.of());}
    players.sort((a,b)->Double.compare(num(b.get("rating")),num(a.get("rating"))));return players;
  }

  /** 按 team_number 分组，仅把真实编号映射为前端展示用的 A/B 标签。 */
  private List<Map<String,Object>> loadRoster(Connection c,long id)throws SQLException{List<Map<String,Object>> out=new ArrayList<>();Map<Integer,String> labels=new LinkedHashMap<>();try(PreparedStatement ps=c.prepareStatement("SELECT mp.steamid,p.name,mp.team_number FROM match_players mp LEFT JOIN players p ON p.steamid=mp.steamid WHERE mp.match_id=? ORDER BY mp.team_number,p.name")){ps.setLong(1,id);ResultSet rs=ps.executeQuery();while(rs.next()){int tn=rs.getInt(3);labels.computeIfAbsent(tn,n->labels.isEmpty()?"A":"B");Map<String,Object> p=new LinkedHashMap<>();p.put("steamid",rs.getString(1));p.put("name",Optional.ofNullable(rs.getString(2)).orElse(rs.getString(1)));p.put("team",labels.get(tn));p.put("teamNumber",tn);p.put("kills",0);p.put("deaths",0);p.put("assists",0);p.put("kda","0-0-0");p.put("adr",0.0);p.put("hs",0.0);p.put("aim",0);p.put("rating",0.0);p.put("we",0);p.put("badges",List.of());out.add(p);}}return out;}

  /**
   * 计算有效敌方伤害。同一正式回合内，同一受害者累计最多贡献 100 点生命伤害，
   * 从而避免多名攻击者对同一受害者的伤害总和超过其可用生命值。
   */
  private Map<String,Double> effectiveDamage(Connection c,long id,long start,long end)throws SQLException{Map<String,Double> out=new HashMap<>(),received=new HashMap<>();try(PreparedStatement ps=c.prepareStatement("SELECT d.attacker_steamid,d.victim_steamid,d.health_damage,1+(SELECT COUNT(*) FROM events re WHERE re.match_id=d.match_id AND re.event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(re.data_json,'$.winner')) IN ('T','CT') AND re.tick<d.tick) FROM damages d JOIN match_players a ON a.match_id=d.match_id AND a.steamid=d.attacker_steamid JOIN match_players v ON v.match_id=d.match_id AND v.steamid=d.victim_steamid WHERE d.match_id=? AND d.tick BETWEEN ? AND ? AND a.team_number<>v.team_number "+validRound("d")+" ORDER BY d.tick,d.id")){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next()){String attacker=rs.getString(1),key=rs.getInt(4)+":"+rs.getString(2);double used=received.getOrDefault(key,0.0),allowed=Math.max(0,Math.min(Math.max(0,rs.getDouble(3)),100-used));if(allowed>0){received.put(key,used+allowed);out.merge(attacker,allowed,Double::sum);}}}return out;}
  private Map<String,Integer> multiKillRounds(Connection c,long id,long start,long end)throws SQLException{Map<String,Integer> out=new HashMap<>();try(PreparedStatement ps=c.prepareStatement("SELECT attacker_steamid,COUNT(*) FROM (SELECT k.attacker_steamid,1+(SELECT COUNT(*) FROM events e WHERE e.match_id=k.match_id AND e.event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(e.data_json,'$.winner')) IN ('T','CT') AND e.tick<k.tick) rn,COUNT(*) kills FROM kills k JOIN match_players a ON a.match_id=k.match_id AND a.steamid=k.attacker_steamid JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.match_id=? AND k.tick BETWEEN ? AND ? AND a.team_number<>v.team_number AND k.attacker_steamid<>k.victim_steamid "+validRound("k")+" GROUP BY k.attacker_steamid,rn HAVING COUNT(*)>=3) x GROUP BY attacker_steamid")){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next())out.put(rs.getString(1),rs.getInt(2));}return out;}
  private Map<String,Object> loadAnalytics(Connection c,long id)throws SQLException{
    long start=scalarLong(c,"SELECT COALESCE(official_start_tick,warmup_end_tick,0) FROM matches WHERE id=?",id),end=scalarLong(c,"SELECT MAX(tick) FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT')",id);if(end<=0)end=scalarLong(c,"SELECT MAX(tick) FROM rounds WHERE match_id=? AND start_type='round_officially_ended'",id);if(end<=0)end=Long.MAX_VALUE;
    Map<String,Object> out=new LinkedHashMap<>();List<Map<String,Object>> weapons=new ArrayList<>(),rounds=new ArrayList<>(),highlights=new ArrayList<>();
    try(PreparedStatement ps=c.prepareStatement("SELECT "+normalizedWeaponSql("k")+" normalized_weapon,COUNT(*) kills,COALESCE(SUM(k.headshot),0) headshots FROM kills k JOIN match_players a ON a.match_id=k.match_id AND a.steamid=k.attacker_steamid JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.match_id=? AND k.tick BETWEEN ? AND ? AND a.team_number<>v.team_number AND k.attacker_steamid<>k.victim_steamid "+validRound("k")+" GROUP BY normalized_weapon ORDER BY kills DESC LIMIT 20")){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next())weapons.add(Map.of("weapon",Optional.ofNullable(rs.getString(1)).orElse("unknown"),"kills",rs.getInt(2),"headshots",rs.getInt(3)));}
    try(PreparedStatement ps=c.prepareStatement("SELECT DISTINCT tick FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT') AND tick BETWEEN ? AND ? ORDER BY tick")){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();int n=1;while(rs.next()){long tick=rs.getLong(1);rounds.add(Map.of("round",n++,"tick",tick,"timeSeconds",Math.max(0,(tick-start)/64)));}}
    try(PreparedStatement ps=c.prepareStatement("SELECT attacker_name,attacker_steamid,rn,kills FROM (SELECT k.attacker_name,k.attacker_steamid,1+(SELECT COUNT(*) FROM events e WHERE e.match_id=k.match_id AND e.event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(e.data_json,'$.winner')) IN ('T','CT') AND e.tick<k.tick) rn,COUNT(*) kills FROM kills k JOIN match_players a ON a.match_id=k.match_id AND a.steamid=k.attacker_steamid JOIN match_players v ON v.match_id=k.match_id AND v.steamid=k.victim_steamid WHERE k.match_id=? AND k.tick BETWEEN ? AND ? AND a.team_number<>v.team_number AND k.attacker_steamid<>k.victim_steamid "+validRound("k")+" GROUP BY k.attacker_name,k.attacker_steamid,rn HAVING COUNT(*)>=3) x ORDER BY kills DESC,rn LIMIT 30")){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next()){Map<String,Object> h=new LinkedHashMap<>();h.put("player",rs.getString(1));h.put("steamid",rs.getString(2));h.put("round",rs.getInt(3));h.put("kills",rs.getInt(4));highlights.add(h);}}
    int ctWins=scalarInt(c,"SELECT COUNT(DISTINCT tick) FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner'))='CT' AND tick BETWEEN ? AND ?",id,start,end),tWins=scalarInt(c,"SELECT COUNT(DISTINCT tick) FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner'))='T' AND tick BETWEEN ? AND ?",id,start,end);
    out.put("weapons",weapons);out.put("rounds",rounds);out.put("highlights",highlights);out.put("clutches",loadClutches(c,id,start,end));out.put("roundCount",rounds.size());out.put("ctWins",ctWins);out.put("tWins",tWins);out.put("startTick",start);out.put("endTick",end==Long.MAX_VALUE?null:end);return out;
  }

  /** 回放每回合存活状态，仅记录进入 1vX 且最终赢下该回合的玩家。 */
  private List<Map<String,Object>> loadClutches(Connection c,long id,long matchStart,long matchEnd)throws SQLException{
    Map<String,Integer> playerTeam=new HashMap<>();Map<String,String> names=new HashMap<>();Map<Integer,LinkedHashSet<String>> rosters=new LinkedHashMap<>();
    try(PreparedStatement ps=c.prepareStatement("SELECT mp.steamid,mp.team_number,p.name FROM match_players mp LEFT JOIN players p ON p.steamid=mp.steamid WHERE mp.match_id=?")){ps.setLong(1,id);ResultSet rs=ps.executeQuery();while(rs.next()){String sid=rs.getString(1);int team=rs.getInt(2);playerTeam.put(sid,team);names.put(sid,Optional.ofNullable(rs.getString(3)).orElse(sid));rosters.computeIfAbsent(team,k->new LinkedHashSet<>()).add(sid);}}
    if(rosters.size()!=2)return List.of();
    List<Long> starts=new ArrayList<>(),ends=new ArrayList<>();
    try(PreparedStatement ps=c.prepareStatement("SELECT DISTINCT tick FROM events WHERE match_id=? AND event_name='round_freeze_end' AND tick BETWEEN ? AND ? ORDER BY tick")){ps.setLong(1,id);ps.setLong(2,matchStart);ps.setLong(3,matchEnd);ResultSet rs=ps.executeQuery();while(rs.next())starts.add(rs.getLong(1));}
    try(PreparedStatement ps=c.prepareStatement("SELECT DISTINCT tick FROM events WHERE match_id=? AND event_name=CASE WHEN EXISTS(SELECT 1 FROM events x WHERE x.match_id=? AND x.event_name='round_end') THEN 'round_end' ELSE 'round_officially_ended' END AND tick BETWEEN ? AND ? ORDER BY tick")){ps.setLong(1,id);ps.setLong(2,id);ps.setLong(3,matchStart);ps.setLong(4,matchEnd);ResultSet rs=ps.executeQuery();while(rs.next())ends.add(rs.getLong(1));}
    List<Map<String,Object>> result=new ArrayList<>();int endIndex=0;
    for(int roundIndex=0;roundIndex<starts.size();roundIndex++){
      long roundStart=starts.get(roundIndex);while(endIndex<ends.size()&&ends.get(endIndex)<roundStart)endIndex++;if(endIndex>=ends.size())break;long roundEnd=ends.get(endIndex++);if(roundIndex+1<starts.size()&&roundEnd>=starts.get(roundIndex+1))continue;
      Map<Integer,LinkedHashSet<String>> alive=new LinkedHashMap<>();for(var entry:rosters.entrySet())alive.put(entry.getKey(),new LinkedHashSet<>(entry.getValue()));Map<String,Integer> candidates=new HashMap<>();
      try(PreparedStatement ps=c.prepareStatement("SELECT attacker_steamid,victim_steamid FROM kills WHERE match_id=? AND tick BETWEEN ? AND ? ORDER BY tick,id")){ps.setLong(1,id);ps.setLong(2,roundStart);ps.setLong(3,roundEnd);ResultSet rs=ps.executeQuery();while(rs.next()){String victim=rs.getString(2);Integer victimTeam=playerTeam.get(victim);if(victimTeam==null)continue;alive.get(victimTeam).remove(victim);for(var entry:alive.entrySet()){if(entry.getValue().size()!=1)continue;int opponents=alive.entrySet().stream().filter(x->!x.getKey().equals(entry.getKey())).mapToInt(x->x.getValue().size()).sum();if(opponents>0)candidates.putIfAbsent(entry.getValue().iterator().next(),opponents);}}}
      Integer winner=null;
      try(PreparedStatement ps=c.prepareStatement("SELECT event_name,player_steamid,JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) FROM events WHERE match_id=? AND tick BETWEEN ? AND ? AND event_name IN ('bomb_defused','bomb_exploded','round_end') ORDER BY tick DESC")){ps.setLong(1,id);ps.setLong(2,roundStart);ps.setLong(3,roundEnd);ResultSet rs=ps.executeQuery();while(rs.next()){String event=rs.getString(1),sid=rs.getString(2);if((event.equals("bomb_defused")||event.equals("bomb_exploded"))&&playerTeam.containsKey(sid)){winner=playerTeam.get(sid);break;}if(event.equals("round_end")&&winner==null){try{String value=rs.getString(3);int side="T".equalsIgnoreCase(value)?2:"CT".equalsIgnoreCase(value)?3:Integer.parseInt(value);boolean swapped=sidesDifferFromFinal(roundIndex+1,ends.size());winner=swapped?rosters.keySet().stream().filter(t->t!=side).findFirst().orElse(null):side;}catch(Exception ignored){}}}}
      if(winner==null){List<Integer> living=alive.entrySet().stream().filter(x->!x.getValue().isEmpty()).map(Map.Entry::getKey).toList();if(living.size()==1)winner=living.get(0);}
      if(winner==null)continue;for(var candidate:candidates.entrySet()){String sid=candidate.getKey();if(Objects.equals(playerTeam.get(sid),winner)&&alive.get(winner).contains(sid)){Map<String,Object> clutch=new LinkedHashMap<>();clutch.put("round",roundIndex+1);clutch.put("player",names.get(sid));clutch.put("steamid",sid);clutch.put("x",candidate.getValue());result.add(clutch);}}
    }
    result.sort((a,b)->Integer.compare((int)b.get("x"),(int)a.get("x")));return result;
  }
  private Map<String,Integer> countBy(Connection c,String sql,long id,long start,long end)throws SQLException{Map<String,Integer> out=new HashMap<>();try(PreparedStatement ps=c.prepareStatement(sql)){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next())if(rs.getString(1)!=null)out.put(rs.getString(1),rs.getInt(2));}return out;}
  private long scalarLong(Connection c,String sql,long id)throws SQLException{try(PreparedStatement ps=c.prepareStatement(sql)){ps.setLong(1,id);ResultSet rs=ps.executeQuery();return rs.next()?rs.getLong(1):0;}}
  private int scalarInt(Connection c,String sql,long id,long start,long end)throws SQLException{try(PreparedStatement ps=c.prepareStatement(sql)){ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();return rs.next()?rs.getInt(1):0;}}
  private Long nullableLong(ResultSet rs,String col)throws SQLException{long v=rs.getLong(col);return rs.wasNull()?null:v;}
  private long firstNonZero(Long... values){for(Long v:values)if(v!=null&&v>0)return v;return 0;}
  /** 常规赛第 12 回合后换边；12:12 后的加时从第 27 回合起每三回合换边。 */
  private boolean sidesDifferFromFinal(int roundNumber,int totalRounds){int swaps=roundNumber<=12&&totalRounds>12?1:0;for(int boundary=27;boundary<totalRounds;boundary+=3)if(roundNumber<=boundary)swaps++;return swaps%2==1;}
  private double num(Object v){return v instanceof Number n?n.doubleValue():0;}
  /**
   * 自定义 WE 是绝对贡献分：4×ADR/100 + 3×KPR + APR + 2×本队回合胜率。
   * 换边前后的阵营胜者会映射回固定 team_number，避免把 T/CT 当成队伍身份。
   */
  private Map<String,Double> loadWe(Connection c,long id,long start,long end,int roundCount,List<Map<String,Object>> players,Map<String,Double> damage,Map<String,int[]> kills,Map<String,Integer> assists)throws SQLException{
    Map<String,Integer> teams=new HashMap<>();for(Map<String,Object> player:players)teams.put(String.valueOf(player.get("steamid")),((Number)player.get("teamNumber")).intValue());
    List<String> roundWinners=new ArrayList<>();
    try(PreparedStatement ps=c.prepareStatement("SELECT tick,JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) winner FROM events WHERE match_id=? AND event_name='round_end' AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.winner')) IN ('T','CT') AND tick BETWEEN ? AND ? GROUP BY tick,winner ORDER BY tick")){
      ps.setLong(1,id);ps.setLong(2,start);ps.setLong(3,end);ResultSet rs=ps.executeQuery();while(rs.next())roundWinners.add(rs.getString(2));
    }
    Map<Integer,Integer> teamWins=new HashMap<>();
    for(int index=0;index<roundWinners.size();index++){
      int side="T".equalsIgnoreCase(roundWinners.get(index))?2:3;
      int winner=sidesDifferFromFinal(index+1,roundWinners.size())?oppositeSide(side):side;
      teamWins.merge(winner,1,Integer::sum);
    }
    Map<String,Double> scores=new HashMap<>();
    for(Map<String,Object> player:players){
      String sid=String.valueOf(player.get("steamid"));int team=teams.get(sid),playerKills=kills.getOrDefault(sid,new int[3])[0],playerAssists=assists.getOrDefault(sid,0);
      double adr=damage.getOrDefault(sid,0.0)/Math.max(1,roundCount),kpr=playerKills/(double)Math.max(1,roundCount),apr=playerAssists/(double)Math.max(1,roundCount),roundWinRate=teamWins.getOrDefault(team,0)/(double)Math.max(1,roundCount);
      double value=4.0*(adr/100.0)+3.0*kpr+apr+2.0*roundWinRate;
      scores.put(sid,Math.round(Math.max(0,Math.min(16,value))*10.0)/10.0);
    }
    return scores;
  }

  /** SQL 片段：事件之后最近的 round_end 必须有有效胜方，借此排除赛后事件。 */
  private String validRound(String alias){return " AND COALESCE((SELECT JSON_UNQUOTE(JSON_EXTRACT(re.data_json,'$.winner')) FROM events re WHERE re.match_id="+alias+".match_id AND re.event_name='round_end' AND re.tick>="+alias+".tick ORDER BY re.tick,re.id LIMIT 1),'') IN ('T','CT') ";}
  /** 在数据库聚合前统一武器别名、皮肤后缀和全部刀具变体。 */
  private String normalizedWeaponSql(String alias){String base="LOWER(SUBSTRING_INDEX(SUBSTRING_INDEX("+alias+".weapon,'_txz',1),'_vip',1))";return "CASE WHEN "+base+"='bayonet' OR "+base+"='knife' OR LEFT("+base+",6)='knife_' THEN 'knife' WHEN "+base+" IN ('m4a1_silencer','m4a1silencer','m4a1slencer') THEN 'm4a1s' WHEN "+base+"='fiveseven' THEN 'fn57' ELSE "+base+" END";}
}
