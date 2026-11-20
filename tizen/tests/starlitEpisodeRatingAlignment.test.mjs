import assert from "node:assert/strict";
import test from "node:test";
import { alignTvEpisodeRatings } from "../js/data/repository/imdbEpisodeRatingsRepository.js";

const ep = (season, episode, released) => ({ season, episode, released });
const rating = (episode, value, airDate) => ({ episode, rating: value, ...(airDate ? { airDate } : {}) });

test("preserves exact season numbering", () => {
  const source = { 1: [rating(1, 7.0)], 2: [rating(1, 8.0)] };
  assert.deepEqual(alignTvEpisodeRatings([ep(1, 1), ep(2, 1)], source), source);
});

test("splits a combined season by chronological episode numbering", () => {
  const source = { 1: [rating(1, 7.0), rating(2, 7.1)], 2: [rating(1, 8.0), rating(2, 8.1)] };
  const episodes = [ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4)];
  assert.deepEqual(alignTvEpisodeRatings(episodes, source), {
    1: [rating(1, 7.0), rating(2, 7.1), rating(3, 8.0), rating(4, 8.1)]
  });
});

test("uses matching air dates when airing totals differ", () => {
  const source = {
    1: [rating(1, 7, "2020-01-01"), rating(2, 8, "2020-01-08")],
    2: [rating(1, 9, "2020-03-01")]
  };
  const result = alignTvEpisodeRatings([
    ep(1, 1, "2020-01-01"), ep(1, 2, "2020-01-08"), ep(1, 3, "2020-02-01"), ep(1, 4, "2020-03-01")
  ], source);
  assert.deepEqual(result[1], [rating(1, 7), rating(2, 8), rating(4, 9)]);
});

test("preserves specials during regular episode remapping", () => {
  const source = { 0: [rating(1, 6)], 1: [rating(1, 7)], 2: [rating(1, 8)] };
  const aligned = alignTvEpisodeRatings([ep(1, 1), ep(1, 2)], source);
  assert.deepEqual(aligned[0], [rating(1, 6)]);
  assert.deepEqual(aligned[1], [rating(1, 7), rating(2, 8)]);
});

test("does not guess when dates and episode totals do not match", () => {
  const source = { 1: [rating(1, 7), rating(2, 8)], 2: [rating(1, 9)] };
  assert.deepEqual(alignTvEpisodeRatings([ep(1, 1), ep(1, 2)], source), source);
});
