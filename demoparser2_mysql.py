import json, math, os, re, sys
from collections import Counter
from itertools import combinations
from datetime import datetime
from pathlib import Path
from demoparser2 import DemoParser
from db_schema import open_application_database
from steam_avatar_scraper import download_avatars

demo=Path(sys.argv[1]); p=DemoParser(str(demo)); header=p.parse_header()
con=open_application_database(); c=con.cursor()
z=p.parse_events(['round_announce_match_start']); start_rows=z[0][1].to_dict(orient='records') if z else []; official_tick=int(start_rows[0].get('tick',0)) if start_rows else 0
recorded_at=datetime.fromtimestamp(demo.stat().st_mtime)
demo_sha256=os.getenv('DEMO_SHA256')
c.execute('SELECT id FROM matches WHERE demo_file_path=%s',(str(demo),)); existing=c.fetchone()
if existing:
 mid=int(existing[0])
 for table in ('kills','damages','bomb_events','events','rounds','match_players'): c.execute(f'DELETE FROM {table} WHERE match_id=%s',(mid,))
 c.execute('UPDATE matches SET map_name=%s,patch_version=%s,server_name=%s,warmup_end_tick=%s,official_start_tick=%s,recorded_at=%s,recording_time_source=%s,demo_sha256=%s WHERE id=%s',(header.get('map_name'),header.get('patch_version'),header.get('server_name'),official_tick,official_tick,recorded_at,'file_last_modified',demo_sha256,mid))
else:
 c.execute('INSERT INTO matches(demo_file_path,map_name,patch_version,server_name,warmup_end_tick,official_start_tick,recorded_at,recording_time_source,demo_sha256) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)',(str(demo),header.get('map_name'),header.get('patch_version'),header.get('server_name'),official_tick,official_tick,recorded_at,'file_last_modified',demo_sha256)); mid=c.lastrowid
def clean(x):
 if isinstance(x,float) and (math.isnan(x) or math.isinf(x)): return None
 if isinstance(x,dict): return {k:clean(v) for k,v in x.items()}
 return x
def normalize_weapon(name):
 if not isinstance(name,str): return name
 normalized=re.sub(r'(?:_txz\d+|_vip)+$','',name.strip().lower(),flags=re.IGNORECASE)
 if normalized=='bayonet' or normalized=='knife' or normalized.startswith('knife_'): return 'knife'
 if normalized in ('m4a1_silencer','m4a1silencer','m4a1slencer'): return 'm4a1s'
 if normalized=='fiveseven': return 'fn57'
 return normalized
player_rows=p.parse_player_info().to_dict(orient='records')
if not player_rows:
 fallback={}
 try:
  death_rows=p.parse_event('player_death',player=['team_num']).sort_values('tick').to_dict(orient='records')
  participants={}; opponent_edges=Counter()
  for row in death_rows:
   for prefix in ('attacker','user','assister'):
    sid=row.get(prefix+'_steamid'); team=row.get(prefix+'_team_num'); name=row.get(prefix+'_name')
    if sid is None or str(sid) in ('0','None','nan'): continue
    sid=str(sid); participants.setdefault(sid,{'steamid':sid,'name':name})
    if team in (2,3): fallback.setdefault(sid,{'steamid':sid,'name':name,'team_number':int(team)})
   attacker=row.get('attacker_steamid'); victim=row.get('user_steamid')
   if attacker is not None and victim is not None and str(attacker) not in ('0','None','nan') and str(victim) not in ('0','None','nan') and str(attacker)!=str(victim):
    opponent_edges[tuple(sorted((str(attacker),str(victim))))]+=1
  unknown=sorted(set(participants)-set(fallback)); known_counts=Counter(row['team_number'] for row in fallback.values())
  if len(participants)==10 and set(known_counts).issubset({2,3}) and known_counts[2]<=5 and known_counts[3]<=5 and len(unknown)==10-len(fallback):
   need_team2=5-known_counts[2]; candidates=[]
   for team2_members in combinations(unknown,need_team2):
    assigned={sid:row['team_number'] for sid,row in fallback.items()}; assigned.update({sid:(2 if sid in team2_members else 3) for sid in unknown})
    score=sum(weight for pair,weight in opponent_edges.items() if assigned.get(pair[0])!=assigned.get(pair[1]))
    candidates.append((score,tuple(team2_members),assigned))
   candidates.sort(key=lambda item:(-item[0],item[1]))
   if candidates and (len(candidates)==1 or candidates[0][0]>candidates[1][0]):
    for sid in unknown: fallback[sid]={**participants[sid],'team_number':candidates[0][2][sid]}
    print('[roster] recovered missing team numbers from unique 5v5 opponent graph:',','.join(unknown))
  player_rows=list(fallback.values())
  print('[roster] parse_player_info empty; recovered',len(player_rows),'players from player_death events')
 except Exception as exc:
  print('[roster] fallback failed:',exc,file=sys.stderr)
for row in player_rows:
 sid=str(row.get('steamid')); c.execute('INSERT INTO players(steamid,name) VALUES(%s,%s) ON DUPLICATE KEY UPDATE name=VALUES(name)',(sid,row.get('name'))); c.execute('INSERT IGNORE INTO match_players(match_id,steamid,team_number) VALUES(%s,%s,%s)',(mid,sid,row.get('team_number')))
rounds=[]
for name in ['round_prestart','round_freeze_end','round_officially_ended']:
 try:
  z=p.parse_events([name]); rows=z[0][1].to_dict(orient='records') if z else []
 except Exception:
  rows=[]
 for row in rows:
  row=clean(row); tick=int(row.get('tick',0) or 0)
  if name in ('round_prestart','round_freeze_end'): c.execute('INSERT INTO rounds(match_id,tick,start_type,round_number,data_json) VALUES(%s,%s,%s,%s,%s)',(mid,tick,name,len(rounds)+1,json.dumps(row,ensure_ascii=False,allow_nan=False))); rounds.append(c.lastrowid)
  else: c.execute('INSERT INTO rounds(match_id,tick,start_type,round_number,data_json) VALUES(%s,%s,%s,%s,%s)',(mid,tick,name,len(rounds)+1,json.dumps(row,ensure_ascii=False,allow_nan=False))); rounds.append(c.lastrowid)
round_winners=[]
for name in ['player_death','player_hurt','bomb_planted','bomb_defused','bomb_exploded','bomb_beginplant','bomb_begindefuse','round_prestart','round_freeze_end','round_end','round_officially_ended','round_announce_match_start','cs_win_panel_match','player_connect_full','player_spawn']:
 try: z=p.parse_events([name]); rows=z[0][1].to_dict(orient='records') if z else []
 except Exception: continue
 for row in rows:
  row=clean(row); raw=json.dumps(row,ensure_ascii=False,default=str,allow_nan=False); tick=int(row.get('tick',0) or 0); rid=None
  if name=='round_end' and str(row.get('winner','')).upper() in ('T','CT'):
   round_winners.append((int(row.get('round',0) or 0),str(row.get('winner')).upper()))
  c.execute('INSERT INTO events(match_id,round_id,event_name,tick,player_steamid,player_name,is_warmup,data_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',(mid,rid,name,tick,row.get('user_steamid'),row.get('user_name'),tick < official_tick,raw))
  if name=='player_death': c.execute('INSERT INTO kills(match_id,round_id,tick,attacker_steamid,attacker_name,victim_steamid,victim_name,weapon,headshot,data_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',(mid,rid,tick,row.get('attacker_steamid'),row.get('attacker_name'),row.get('user_steamid'),row.get('user_name'),normalize_weapon(row.get('weapon')),bool(row.get('headshot',False)),raw))
  elif name=='player_hurt': c.execute('INSERT INTO damages(match_id,round_id,tick,attacker_steamid,attacker_name,victim_steamid,victim_name,health_damage,armor_damage,data_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)',(mid,rid,tick,row.get('attacker_steamid'),row.get('attacker_name'),row.get('user_steamid'),row.get('user_name'),row.get('dmg_health'),row.get('dmg_armor'),raw))
  elif name.startswith('bomb_'): c.execute('INSERT INTO bomb_events(match_id,round_id,tick,event_name,player_steamid,player_name,site,data_json) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)',(mid,rid,tick,name,row.get('user_steamid'),row.get('user_name'),row.get('site'),raw))
team_scores={2:0,3:0}; last_round=max((number for number,_ in round_winners),default=0)
for number,winner in round_winners:
 side=2 if winner=='T' else 3 if winner=='CT' else 0
 if side and number<=12<last_round: side=5-side
 if side: team_scores[side]+=1
c.execute("UPDATE matches SET final_score_t=%s,final_score_ct=%s WHERE id=%s",(team_scores[2],team_scores[3],mid))
con.commit(); print('OK MySQL match_id',mid,'map',header.get('map_name')); con.close()
try:
 print('Steam',download_avatars([str(row.get('steamid')) for row in player_rows]))
except Exception as exc:
 print('[avatar] skipped:',exc,file=sys.stderr)
