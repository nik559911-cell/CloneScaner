-- Эквивалент Room-сущностей приложения «Сканер». Проверено на SQLite.
pragma foreign_keys = on;

create table folders (
  id          text primary key,              -- UUID
  name        text not null,
  created_at  integer not null               -- epoch millis, UTC
);

create table documents (
  id          text primary key,
  folder_id   text references folders(id) on delete set null,
  title       text not null,
  is_draft    integer not null default 1,    -- 1, пока идёт серия съёмки (F02)
  created_at  integer not null,
  updated_at  integer not null
);
create index idx_documents_folder  on documents(folder_id);
create index idx_documents_updated on documents(updated_at desc);

create table pages (
  id             text primary key,
  document_id    text not null references documents(id) on delete cascade,
  position       integer not null,           -- 0..n-1
  original_path  text not null,              -- снимок как есть (EXIF уже применён)
  processed_path text,                       -- null, пока не обработана
  thumb_path     text,
  quad           text,                       -- JSON: 4 точки [x,y] в координатах оригинала, null = весь кадр
  rotation       integer not null default 0 check (rotation in (0,90,180,270)),
  filter         text not null default 'white'
                 check (filter in ('original','white','lighten','gray','bw')),
  width          integer,
  height         integer,
  created_at     integer not null,
  updated_at     integer not null,
  unique (document_id, position)
);
create index idx_pages_document on pages(document_id, position);
