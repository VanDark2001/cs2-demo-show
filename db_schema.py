import os
import re

import pymysql


_SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9_$-]+$")


def settings():
    database = os.getenv("MYSQL_DATABASE", "csdemo")
    user = os.getenv("MYSQL_USER", "csdemo")
    if not _SAFE_IDENTIFIER.fullmatch(database):
        raise ValueError("MYSQL_DATABASE contains unsupported characters")
    if not user:
        raise ValueError("MYSQL_USER cannot be empty")
    return {
        "host": os.getenv("MYSQL_HOST", "127.0.0.1"),
        "port": int(os.getenv("MYSQL_PORT", "3306")),
        "database": database,
        "user": user,
        "password": os.getenv("MYSQL_PASSWORD", ""),
    }


def connect(user, password, database=None, autocommit=False):
    config = settings()
    options = {
        "host": config["host"],
        "port": config["port"],
        "user": user,
        "password": password,
        "charset": "utf8mb4",
        "autocommit": autocommit,
    }
    if database:
        options["database"] = database
    return pymysql.connect(**options)


def _database_identifier(database):
    return f"`{database}`"


def ensure_database(connection, database):
    with connection.cursor() as cursor:
        cursor.execute(
            f"CREATE DATABASE IF NOT EXISTS {_database_identifier(database)} "
            "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        )


def ensure_schema(connection):
    with connection.cursor() as cursor:
        cursor.execute(
            """CREATE TABLE IF NOT EXISTS matches(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            demo_file_path VARCHAR(768) UNIQUE,
            map_name VARCHAR(128),
            patch_version VARCHAR(64),
            server_name VARCHAR(255),
            final_score_t INT,
            final_score_ct INT,
            warmup_end_tick BIGINT,
            official_start_tick BIGINT,
            recorded_at DATETIME,
            recording_time_source VARCHAR(32),
            demo_sha256 CHAR(64),
            imported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )"""
        )
        for column, column_type in (
            ("recorded_at", "DATETIME"),
            ("recording_time_source", "VARCHAR(32)"),
            ("demo_sha256", "CHAR(64)"),
        ):
            try:
                cursor.execute(f"ALTER TABLE matches ADD COLUMN {column} {column_type} NULL")
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1060:
                    raise

        cursor.execute(
            "CREATE TABLE IF NOT EXISTS players("
            "steamid VARCHAR(32) PRIMARY KEY,name VARCHAR(255))"
        )
        cursor.execute(
            """CREATE TABLE IF NOT EXISTS match_players(
            match_id BIGINT,
            steamid VARCHAR(32),
            team_number INT,
            total_damage INT NULL,
            PRIMARY KEY(match_id,steamid),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE,
            FOREIGN KEY(steamid) REFERENCES players(steamid)
            )"""
        )
        try:
            cursor.execute("ALTER TABLE match_players ADD COLUMN total_damage INT NULL")
        except pymysql.err.OperationalError as error:
            if error.args[0] != 1060:
                raise
        cursor.execute(
            """CREATE TABLE IF NOT EXISTS rounds(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            match_id BIGINT,
            tick BIGINT,
            start_type VARCHAR(40),
            end_tick BIGINT NULL,
            round_number INT,
            data_json JSON,
            INDEX(match_id),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
            )"""
        )
        for column, column_type in (("start_type", "VARCHAR(40)"), ("round_number", "INT")):
            try:
                cursor.execute(f"ALTER TABLE rounds ADD COLUMN {column} {column_type} NULL")
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1060:
                    raise

        cursor.execute(
            """CREATE TABLE IF NOT EXISTS events(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            match_id BIGINT,
            round_id BIGINT NULL,
            event_name VARCHAR(128),
            tick BIGINT,
            player_steamid VARCHAR(32),
            player_name VARCHAR(255),
            team_number INT,
            is_warmup BOOLEAN DEFAULT FALSE,
            data_json JSON,
            INDEX(match_id),
            INDEX(round_id),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
            )"""
        )
        for column, column_type in (
            ("round_id", "BIGINT"),
            ("player_steamid", "VARCHAR(32)"),
            ("player_name", "VARCHAR(255)"),
            ("team_number", "INT"),
            ("is_warmup", "BOOLEAN"),
        ):
            try:
                cursor.execute(f"ALTER TABLE events ADD COLUMN {column} {column_type} NULL")
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1060:
                    raise

        cursor.execute(
            """CREATE TABLE IF NOT EXISTS kills(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            match_id BIGINT,
            round_id BIGINT NULL,
            tick BIGINT,
            attacker_steamid VARCHAR(32),
            attacker_name VARCHAR(255),
            victim_steamid VARCHAR(32),
            victim_name VARCHAR(255),
            weapon VARCHAR(128),
            headshot BOOLEAN,
            data_json JSON,
            INDEX(match_id),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
            )"""
        )
        cursor.execute(
            """CREATE TABLE IF NOT EXISTS damages(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            match_id BIGINT,
            round_id BIGINT NULL,
            tick BIGINT,
            attacker_steamid VARCHAR(32),
            attacker_name VARCHAR(255),
            victim_steamid VARCHAR(32),
            victim_name VARCHAR(255),
            health_damage INT,
            armor_damage INT,
            data_json JSON,
            INDEX(match_id),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
            )"""
        )
        cursor.execute(
            """CREATE TABLE IF NOT EXISTS bomb_events(
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            match_id BIGINT,
            round_id BIGINT NULL,
            tick BIGINT,
            event_name VARCHAR(64),
            player_steamid VARCHAR(32),
            player_name VARCHAR(255),
            site VARCHAR(8),
            data_json JSON,
            INDEX(match_id),
            FOREIGN KEY(match_id) REFERENCES matches(id) ON DELETE CASCADE
            )"""
        )
        for table, name, columns in (
            ("events", "idx_events_match_name_tick", "match_id,event_name,tick"),
            ("kills", "idx_kills_match_tick", "match_id,tick"),
            ("damages", "idx_damages_match_tick", "match_id,tick"),
        ):
            try:
                cursor.execute(f"ALTER TABLE {table} ADD INDEX {name} ({columns})")
            except pymysql.err.OperationalError as error:
                if error.args[0] != 1061:
                    raise
    connection.commit()


def bootstrap_mysql():
    config = settings()
    database = config["database"]

    try:
        application = connect(config["user"], config["password"])
        try:
            ensure_database(application, database)
            application.commit()
        finally:
            application.close()
    except pymysql.MySQLError:
        admin_user = os.getenv("MYSQL_ADMIN_USER", "root")
        admin_password = os.getenv("MYSQL_ADMIN_PASSWORD", config["password"])
        account_host = os.getenv("MYSQL_APP_USER_HOST", "localhost")
        admin = connect(admin_user, admin_password, autocommit=True)
        try:
            ensure_database(admin, database)
            account = f"{admin.escape(config['user'])}@{admin.escape(account_host)}"
            with admin.cursor() as cursor:
                cursor.execute(
                    f"CREATE USER IF NOT EXISTS {account} IDENTIFIED BY %s",
                    (config["password"],),
                )
                cursor.execute(
                    f"ALTER USER {account} IDENTIFIED BY %s",
                    (config["password"],),
                )
                cursor.execute(
                    f"GRANT ALL PRIVILEGES ON {_database_identifier(database)}.* TO {account}"
                )
                cursor.execute("FLUSH PRIVILEGES")
        finally:
            admin.close()

    application = connect(config["user"], config["password"], database)
    try:
        ensure_schema(application)
    finally:
        application.close()
    return config


def open_application_database():
    config = settings()
    connection = connect(config["user"], config["password"], config["database"])
    ensure_schema(connection)
    return connection
