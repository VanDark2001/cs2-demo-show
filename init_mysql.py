import os
from pathlib import Path

from db_schema import bootstrap_mysql


def load_local_env():
    env_file = Path(__file__).with_name(".env")
    if not env_file.is_file():
        return
    for raw_line in env_file.read_text(encoding="utf-8-sig").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


if __name__ == "__main__":
    load_local_env()
    config = bootstrap_mysql()
    print(
        "MySQL initialized: "
        f"{config['host']}:{config['port']}/{config['database']} "
        f"(application user: {config['user']})"
    )
