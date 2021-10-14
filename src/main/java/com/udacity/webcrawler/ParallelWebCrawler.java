package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParserFactory;

import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * A concrete implementation of {@link WebCrawler} that runs multiple threads on a
 * {@link ForkJoinPool} to fetch and process multiple web pages in parallel.
 */
final class ParallelWebCrawler implements WebCrawler {
    private final Clock clock;
    private final Duration timeout;
    private final int popularWordCount;
    private final ForkJoinPool pool;
    private final List<Pattern> ignoredUrls;
    private final int maxDepth;
    private final PageParserFactory factory;

    @Inject
    ParallelWebCrawler(
            Clock clock,
            @Timeout Duration timeout,
            @PopularWordCount int popularWordCount,
            @TargetParallelism int threadCount,
            @IgnoredUrls List<Pattern> ignoredUrls,
            @MaxDepth int maxDepth,
            PageParserFactory factory
    ) {
        this.clock = clock;
        this.timeout = timeout;
        this.popularWordCount = popularWordCount;
        this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
        this.ignoredUrls = ignoredUrls;
        this.maxDepth = maxDepth;
        this.factory = factory;
    }

    @Override
    public CrawlResult crawl(List<String> startingUrls) {
        var deadline = clock.instant().plus(timeout);
        var counts = new ConcurrentHashMap<String, Integer>();
        var visitedUrls = new ConcurrentSkipListSet<String>();

        for (var url : startingUrls) {
            pool.invoke(new ParallelWebCrawlerTask(url, deadline, maxDepth, counts, visitedUrls));
        }

        if (counts.isEmpty()) {
            return new CrawlResult
                    .Builder()
                    .setWordCounts(counts)
                    .setUrlsVisited(visitedUrls.size())
                    .build();
        }

        return new CrawlResult
                .Builder()
                .setWordCounts(WordCounts.sort(counts, popularWordCount))
                .setUrlsVisited(visitedUrls.size())
                .build();
    }

    @Override
    public int getMaxParallelism() {
        return Runtime.getRuntime().availableProcessors();
    }

    public class ParallelWebCrawlerTask extends RecursiveTask<Boolean> {
        private final String url;
        private final Instant deadline;
        private final int maxDepth;
        private final ConcurrentMap<String, Integer> counts;
        private final ConcurrentSkipListSet<String> visitedUrls;

        public ParallelWebCrawlerTask(String url, Instant deadline, int maxDepth, ConcurrentMap<String, Integer> counts, ConcurrentSkipListSet<String> visitedUrls) {
            this.url = url;
            this.deadline = deadline;
            this.maxDepth = maxDepth;
            this.counts = counts;
            this.visitedUrls = visitedUrls;
        }

        @Override
        protected Boolean compute() {
            if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
                return false;
            }

            for (Pattern pattern : ignoredUrls) {
                if (pattern.matcher(url).matches()) {
                    return false;
                }
            }

            if (!visitedUrls.add(url)) {
                return false;
            }

            var result = factory.get(url).parse();

            for (ConcurrentMap.Entry<String, Integer> c : result.getWordCounts().entrySet()) {
                counts.compute(c.getKey(), (k, v) -> (v == null) ? c.getValue() : c.getValue() + v);
            }

            List<ParallelWebCrawlerTask> subtasks = new ArrayList<>();
            for (var link : result.getLinks()) {
                subtasks.add(new ParallelWebCrawlerTask(link, deadline, maxDepth - 1, counts, visitedUrls));
            }

            invokeAll(subtasks);

            return true;
        }
    }
}
