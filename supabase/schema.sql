-- FlappyBoards leaderboard schema v2.1 for Supabase.
-- Run in the Supabase dashboard: SQL Editor → New query → paste → Run.
-- Idempotent: safe to re-run, recreates everything.

drop view  if exists public.leaderboard cascade;
drop view  if exists public.leaderboard_week cascade;
drop function if exists public.submit_score(text, int) cascade;
drop function if exists public.submit_score(text, int, int, uuid) cascade;
drop function if exists public.my_place(text) cascade;
drop function if exists public.my_place(text, boolean) cascade;
drop table if exists public.scores cascade;
drop table if exists public.players cascade;

-- Nicknames are claimed by devices: a nickname belongs to the first
-- device_id that used it, so nobody can hijack someone else's handle.
create table public.players (
    device_id  uuid primary key,
    nickname   text        not null unique check (char_length(nickname) between 1 and 24),
    created_at timestamptz not null default now()
);

create table public.scores (
    id         bigint generated always as identity primary key,
    device_id  uuid        not null,
    nickname   text        not null check (char_length(nickname) between 1 and 24),
    score      int         not null check (score between 0 and 100000),
    accuracy   int         not null default 0 check (accuracy between 0 and 100),
    created_at timestamptz not null default now()
);

create index if not exists scores_created_idx on public.scores (created_at desc);
create index if not exists scores_nick_created_idx on public.scores (nickname, created_at desc);

alter table public.players enable row level security;
alter table public.scores  enable row level security;

-- Base tables: NO direct anon/authenticated access. The device_id is a
-- pseudo-identity — if it were readable, anyone could submit scores
-- impersonating another device/nickname. All reads go through the views
-- below (they execute with the table owner's rights) and the RPCs.
-- Supabase grants ALL on new tables to anon by default, so revoke explicitly.
revoke select on public.players from anon, authenticated;
revoke select on public.scores  from anon, authenticated;

-- All-time best run per nickname (score + that run's accuracy + date).
create or replace view public.leaderboard as
select nickname, best_score, accuracy, last_played
from (
    select distinct on (nickname)
        nickname,
        score       as best_score,
        accuracy,
        created_at  as last_played
    from public.scores
    order by nickname, score desc, created_at desc
) best
order by best_score desc;

-- Same, but only runs from the last 7 days (weekly season).
create or replace view public.leaderboard_week as
select nickname, best_score, accuracy, last_played
from (
    select distinct on (nickname)
        nickname,
        score       as best_score,
        accuracy,
        created_at  as last_played
    from public.scores
    where created_at > now() - interval '7 days'
    order by nickname, score desc, created_at desc
) best
order by best_score desc;

-- Public read surface: the two leaderboard views and the RPCs only.
grant select on public.leaderboard      to anon, authenticated;
grant select on public.leaderboard_week to anon, authenticated;

-- The only write path. Validates input, rate-limits per device and
-- enforces nickname ownership. It is "anti-idiot", not "anti-cheater":
-- the publishable key is public anyway.
create or replace function public.submit_score(
    p_nickname text,
    p_score    int,
    p_accuracy int,
    p_device_id uuid
)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    recent   int;
    owner_id uuid;
begin
    if p_nickname is null or char_length(trim(p_nickname)) not between 1 and 24 then
        raise exception 'invalid nickname';
    end if;
    if p_score is null or p_score not between 1 and 100000 then
        raise exception 'invalid score';
    end if;
    if p_accuracy is null or p_accuracy not between 0 and 100 then
        p_accuracy := 0;
    end if;
    if p_device_id is null then
        raise exception 'invalid device';
    end if;
    p_nickname := trim(p_nickname);

    -- spam guard: max 10 submissions per minute per device
    select count(*) into recent
    from scores
    where device_id = p_device_id
      and created_at > now() - interval '1 minute';
    if recent >= 10 then
        raise exception 'too many submissions, slow down';
    end if;

    -- nickname ownership: belongs to the first device that claimed it
    select device_id into owner_id from players where nickname = p_nickname;
    if owner_id is not null and owner_id <> p_device_id then
        raise exception 'nickname already taken';
    end if;

    insert into players (device_id, nickname)
    values (p_device_id, p_nickname)
    on conflict (device_id) do update set nickname = excluded.nickname;

    insert into scores (device_id, nickname, score, accuracy)
    values (p_device_id, p_nickname, p_score, p_accuracy);
end;
$$;
grant execute on function public.submit_score(text, int, int, uuid) to anon, authenticated;

-- Rank of a player, even outside the top-20 (null if the player has no runs).
create or replace function public.my_place(p_nickname text, p_weekly boolean default false)
returns int
language plpgsql
security definer
set search_path = public
as $$
declare
    place int;
begin
    if p_weekly then
        select r.place into place
        from (
            select row_number() over (order by best_score desc) as place, nickname
            from (
                select nickname, max(score) as best_score
                from scores
                where created_at > now() - interval '7 days'
                group by nickname
            ) b
        ) r
        where r.nickname = trim(p_nickname);
    else
        select r.place into place
        from (
            select row_number() over (order by best_score desc) as place, nickname
            from (
                select nickname, max(score) as best_score
                from scores
                group by nickname
            ) b
        ) r
        where r.nickname = trim(p_nickname);
    end if;
    return place;
end;
$$;
grant execute on function public.my_place(text, boolean) to anon, authenticated;
