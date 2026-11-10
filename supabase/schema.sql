-- FlappyBrains leaderboard schema for Supabase.
-- Run this in the Supabase dashboard: SQL Editor → New query → paste → Run.

create table if not exists public.scores (
    id         bigint generated always as identity primary key,
    nickname   text        not null check (char_length(nickname) between 1 and 24),
    score      int         not null check (score between 0 and 100000),
    created_at timestamptz not null default now()
);

create index if not exists scores_nick_created_idx on public.scores (nickname, created_at desc);

-- Anyone may read scores and submit new ones, but never edit or delete.
alter table public.scores enable row level security;

drop policy if exists "scores are public" on public.scores;
create policy "scores are public" on public.scores for select using (true);

drop policy if exists "anyone can submit" on public.scores;
create policy "anyone can submit" on public.scores for insert with check (true);

-- Best score per nickname: the plugin reads this view as the leaderboard.
create or replace view public.leaderboard as
select nickname,
       max(score)     as best_score,
       max(created_at) as last_played
from public.scores
group by nickname;

grant select on public.leaderboard to anon, authenticated;

-- The plugin submits scores through this RPC so we can apply basic
-- server-side sanity checks. It is "anti-idiot", not "anti-cheater":
-- the anon key is public anyway.
create or replace function public.submit_score(p_nickname text, p_score int)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    recent int;
begin
    if p_nickname is null or char_length(trim(p_nickname)) not between 1 and 24 then
        raise exception 'invalid nickname';
    end if;
    if p_score is null or p_score not between 1 and 100000 then
        raise exception 'invalid score';
    end if;

    -- Max 10 submissions per minute per nickname (spam guard).
    select count(*) into recent
    from scores
    where nickname = trim(p_nickname)
      and created_at > now() - interval '1 minute';
    if recent >= 10 then
        raise exception 'too many submissions, slow down';
    end if;

    insert into scores (nickname, score) values (trim(p_nickname), p_score);
end;
$$;

grant execute on function public.submit_score(text, int) to anon, authenticated;
