/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.junit.Assert;
import org.junit.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.core.publisher.ParallelFlux;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import reactor.test.subscriber.AssertSubscriber;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
public class HooksTest {

	void simpleFlux(){
		Flux.just(1)
		    .map(d -> d + 1)
		    .doOnNext(d -> {throw new RuntimeException("test");})
		    .collectList()
		    .onErrorReturn(Collections.singletonList(2))
		    .block();
	}

	static final class TestException extends RuntimeException {

		public TestException(String message) {
			super(message);
		}
	}

	@Test
	public void errorHooks() throws Exception {

		Hooks.onOperatorError((e, s) -> new TestException(s.toString()));
		Hooks.onNextDropped(d -> {
			throw new TestException(d.toString());
		});
		Hooks.onErrorDropped(e -> {
			throw new TestException("errorDrop");
		});

		Throwable w = Operators.onOperatorError(null, new Exception(), "hello");

		Assert.assertTrue(w instanceof TestException);
		Assert.assertTrue(w.getMessage()
		                   .equals("hello"));

		try {
			Operators.onNextDropped("hello");
			Assert.fail();
		}
		catch (Throwable t) {
			t.printStackTrace();
			Assert.assertTrue(t instanceof TestException);
			Assert.assertTrue(t.getMessage()
			                   .equals("hello"));
		}

		try {
			Operators.onErrorDropped(new Exception());
			Assert.fail();
		}
		catch (Throwable t) {
			Assert.assertTrue(t instanceof TestException);
			Assert.assertTrue(t.getMessage()
			                   .equals("errorDrop"));
		}

		Hooks.resetOnOperatorError();
		Hooks.resetOnNextDropped();
		Hooks.resetOnErrorDropped();
	}

	@Test
	public void accumulatingHooks() throws Exception {
		AtomicReference<String> ref = new AtomicReference<>();
		Hooks.onNextDropped(d -> {
			ref.set(d.toString());
		});
		Hooks.onNextDropped(d -> {
			ref.set(ref.get()+"bar");
		});

		Operators.onNextDropped("foo");

		assertThat(ref.get()).isEqualTo("foobar");

		Hooks.onErrorDropped(d -> {
			ref.set(d.getMessage());
		});
		Hooks.onErrorDropped(d -> {
			ref.set(ref.get()+"bar");
		});

		Operators.onErrorDropped(new Exception("foo"));

		assertThat(ref.get()).isEqualTo("foobar");

		Hooks.resetOnErrorDropped();


		Hooks.onOperatorError((error, d) -> {
			ref.set(d.toString());
			return new Exception("bar");
		});
		Hooks.onOperatorError((error, d) -> {
			ref.set(ref.get()+error.getMessage());
			return error;
		});

		Operators.onOperatorError(null, null, "foo");

		assertThat(ref.get()).isEqualTo("foobar");

		Hooks.resetOnOperatorError();


		AtomicReference<Publisher> hook = new AtomicReference<>();
		AtomicReference<Object> hook2 = new AtomicReference<>();
		Hooks.onEachOperator(h -> {
			hook.set(TestPublisher.create().flux());
			return hook.get();
		});
		Hooks.onEachOperator(h -> {
			hook2.set(h);
			return h;
		});

		Flux.just("test").filter(d -> true).subscribe();

		assertThat(hook.get()).isNotNull().isEqualTo(hook2.get());
		Hooks.resetOnEachOperator();

		hook.set(null);
		hook2.set(null);

		Hooks.onLastOperator(h -> {
			hook.set(TestPublisher.create().flux());
			return hook.get();
		});
		Hooks.onLastOperator(h -> {
			hook2.set(h);
			return h;
		});

		Flux.just("test").filter(d -> true).subscribe();

		assertThat(hook.get()).isNotNull().isEqualTo(hook2.get());

		Hooks.resetOnLastOperator();
	}


	@Test
	public void parallelModeFused() {
		Hooks.onOperatorDebug();

		Hooks.onEachOperator(p -> {
			System.out.println(Scannable.from(p).operatorName());
			return p;
		});

		Flux<Integer> source = Mono.just(1)
		                           .flux()
		                           .repeat(1000)
		                           .publish()
		                           .autoConnect();
		int ncpu = Math.max(8,
				Runtime.getRuntime()
				       .availableProcessors());

			Scheduler scheduler = Schedulers.newParallel("test", ncpu);

			try {
				Flux<Integer> result = ParallelFlux.from(source, ncpu)
				                                   .runOn(scheduler)
				                                   .map(v -> v + 1)
				                                   .log("test", Level.INFO, true, SignalType.ON_SUBSCRIBE)
				                                   .sequential();

				AssertSubscriber<Integer> ts = AssertSubscriber.create();

				result.subscribe(ts);

				ts.await(Duration.ofSeconds(10));

				ts.assertSubscribed()
				  .assertValueCount(1000)
				  .assertComplete()
				  .assertNoError();
			}
			finally {
				Hooks.resetOnEachOperator();
				scheduler.dispose();
			}

	}

	@Test
	public void verboseExtension() {
		Queue<String> q = new LinkedTransferQueue<>();
		Hooks.onEachOperator(p -> {
			q.offer(p.toString());
			return p;
		});
		Hooks.onOperatorDebug();

		simpleFlux();

		assertThat(q.toArray()).containsExactly(
				"FluxJust",
				"FluxMapFuseable",
				"FluxPeekFuseable",
				"MonoCollectList",
				"MonoOnErrorResume",
				"MonoJust"
				);


		q.clear();
		Hooks.resetOnEachOperator();

		Hooks.onEachOperator(p -> {
			q.offer(p.toString());
			return p;
		});

		simpleFlux();

		assertThat(q.toArray()).containsExactly(
				"FluxJust",
				"FluxMapFuseable",
				"FluxPeekFuseable",
				"MonoCollectList",
				"MonoOnErrorResume",
				"MonoJust");

		q.clear();
		Hooks.resetOnEachOperator();

		simpleFlux();

		assertThat(q.toArray()).isEmpty();

		Hooks.resetOnEachOperator();
	}


	@Test
	public void testTrace() throws Exception {
		Hooks.onOperatorDebug();
		try {
			Mono.fromCallable(() -> {
				throw new RuntimeException();
			})
			    .map(d -> d)
			    .block();
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains("MonoCallable"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}


	@Test
	public void testTrace2() throws Exception {
		Hooks.onOperatorDebug();

		try {
			Mono.just(1)
			    .map(d -> {
				    throw new RuntimeException();
			    })
			    .filter(d -> true)
			    .doOnNext(d -> System.currentTimeMillis())
			    .map(d -> d)
			    .block();
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("HooksTest.java:"));
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains("|_\tMono.map" +
					"(HooksTest.java:"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}

	@Test
	public void testTrace3() throws Exception {
		Hooks.onOperatorDebug();
		try {
			Flux.just(1)
			    .map(d -> {
				    throw new RuntimeException();
			    })
			    .share()
			    .filter(d -> true)
			    .doOnNext(d -> System.currentTimeMillis())
			    .map(d -> d)
			    .blockLast();
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("HooksTest.java:"));
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains("|_\tFlux" +
					".share" +
					"(HooksTest.java:"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}

	@Test
	public void testTraceDefer() throws Exception {
		Hooks.onOperatorDebug();
		try {
			Mono.defer(() -> Mono.just(1)
			                     .flatMap(d -> Mono.error(new RuntimeException()))
			                     .filter(d -> true)
			                     .doOnNext(d -> System.currentTimeMillis())
			                     .map(d -> d))
			    .block();
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("HooksTest.java:"));
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains("|_\tMono" +
					".flatMap" +
					"(HooksTest.java:"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}

	@Test
	public void testTraceComposed() throws Exception {
		Hooks.onOperatorDebug();
		try {
			Mono.just(1)
			    .flatMap(d -> Mono.error(new RuntimeException()))
			    .filter(d -> true)
			    .doOnNext(d -> System.currentTimeMillis())
			    .map(d -> d)
			    .block();
		}
		catch (Exception e) {
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage()
			                                      .contains("HooksTest.java:"));
			Assert.assertTrue(e.getSuppressed()[0].getMessage()
			                                      .contains("|_\tMono" + ".flatMap" + "(HooksTest.java:"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}

	@Test
	public void testTraceComposed2() throws Exception {
		Hooks.onOperatorDebug();
		try {
			Flux.just(1)
			    .flatMap(d -> {
				    throw new RuntimeException();
			    })
			    .filter(d -> true)
			    .doOnNext(d -> System.currentTimeMillis())
			    .map(d -> d)
			    .blockLast();
		}
		catch(Exception e){
			e.printStackTrace();
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("HooksTest.java:"));
			Assert.assertTrue(e.getSuppressed()[0].getMessage().contains("|_\tFlux" +
					".flatMap" +
					"(HooksTest.java:"));
			return;
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
		throw new IllegalStateException();
	}

	@Test
	public void testOnLastPublisher() throws Exception {
		List<Publisher> l = new ArrayList<>();
		Hooks.onLastOperator(p -> {
			System.out.println(Scannable.from(p).parents().count());
			System.out.println(Scannable.from(p).operatorName());
			l.add(p);
			return p;
		});
		StepVerifier.create(Flux.just(1, 2, 3)
		                        .map(m -> m)
		                        .takeUntilOther(Mono.never())
		                        .flatMap(d -> Mono.just(d).hide()))
		            .expectNext(1, 2, 3)
		            .verifyComplete();

		Hooks.resetOnLastOperator();

		assertThat(l).hasSize(5);
	}

	@Test
	public void testMultiReceiver() throws Exception {
		Hooks.onOperatorDebug();
		try {

			ConnectableFlux<?> t = Flux.empty()
			    .then(Mono.defer(() -> {
				    throw new RuntimeException();
			    })).flux().publish();

			t.map(d -> d).subscribe(null,
					e -> Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
							("\t|_\tFlux.publish")));

			t.filter(d -> true).subscribe(null, e -> Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("\t\t|_\tFlux.publish")));
			t.distinct().subscribe(null, e -> Assert.assertTrue(e.getSuppressed()[0].getMessage().contains
					("\t\t\t|_\tFlux.publish")));

			t.connect();
		}
		finally {
			Hooks.resetOnOperatorDebug();
		}
	}

	@Test
	public void lastOperatorTest() {
		Hooks.onLastOperator(Operators.lift((sc, sub) ->
				new CoreSubscriber<Object>(){
					@Override
					public void onSubscribe(Subscription s) {
						sub.onSubscribe(s);
					}

					@Override
					public void onNext(Object o) {
						sub.onNext(((Integer)o) + 1);
					}

					@Override
					public void onError(Throwable t) {
						sub.onError(t);
					}

					@Override
					public void onComplete() {
						sub.onComplete();
					}
				}));

		StepVerifier.create(Flux.just(1, 2, 3)
		                        .log()
		                        .log())
		            .expectNext(2, 3, 4)
		            .verifyComplete();

		StepVerifier.create(Mono.just(1)
		                        .log()
		                        .log())
		            .expectNext(2)
		            .verifyComplete();

		StepVerifier.create(ParallelFlux.from(Mono.just(1), Mono.just(1))
		                        .log()
		                        .log())
		            .expectNext(2, 2)
		            .verifyComplete();

		Hooks.resetOnLastOperator();
	}

	@Test
	public void lastOperatorFilterTest() {
		Hooks.onLastOperator(Operators.lift(sc -> sc.tags()
		                                            .anyMatch(t -> t.getT1()
		                                                            .contains("metric")),
				(sc, sub) -> new CoreSubscriber<Object>() {
					@Override
					public void onSubscribe(Subscription s) {
						sub.onSubscribe(s);
					}

					@Override
					public void onNext(Object o) {
						sub.onNext(((Integer) o) + 1);
					}

					@Override
					public void onError(Throwable t) {
						sub.onError(t);
					}

					@Override
					public void onComplete() {
						sub.onComplete();
					}
				}));

		StepVerifier.create(Flux.just(1, 2, 3)
		                        .tag("metric", "test")
		                        .log()
		                        .log())
		            .expectNext(2, 3, 4)
		            .verifyComplete();

		StepVerifier.create(Mono.just(1)
		                        .tag("metric", "test")
		                        .log()
		                        .log())
		            .expectNext(2)
		            .verifyComplete();

		StepVerifier.create(ParallelFlux.from(Mono.just(1), Mono.just(1))
		                                .tag("metric", "test")
		                                .log()
		                                .log())
		            .expectNext(2, 2)
		            .verifyComplete();

		StepVerifier.create(Flux.just(1, 2, 3)
		                        .log()
		                        .log())
		            .expectNext(1, 2, 3)
		            .verifyComplete();

		StepVerifier.create(Mono.just(1)
		                        .log()
		                        .log())
		            .expectNext(1)
		            .verifyComplete();

		StepVerifier.create(ParallelFlux.from(Mono.just(1), Mono.just(1))
		                                .log()
		                                .log())
		            .expectNext(1, 1)
		            .verifyComplete();

		Hooks.resetOnLastOperator();
	}

	@Test
	public void eachOperatorTest() {
		Hooks.onEachOperator(Operators.lift((sc, sub) ->
				new CoreSubscriber<Object>(){
					@Override
					public void onSubscribe(Subscription s) {
						sub.onSubscribe(s);
					}

					@Override
					public void onNext(Object o) {
						sub.onNext(((Integer)o) + 1);
					}

					@Override
					public void onError(Throwable t) {
						sub.onError(t);
					}

					@Override
					public void onComplete() {
						sub.onComplete();
					}
				}));

		StepVerifier.create(Flux.just(1, 2, 3)
		                        .log()
		                        .log())
		            .expectNext(4, 5, 6)
		            .verifyComplete();

		StepVerifier.create(Mono.just(1)
		                        .log()
		                        .log())
		            .expectNext(4)
		            .verifyComplete();

		StepVerifier.create(ParallelFlux.from(Mono.just(1), Mono.just(1))
		                                .log()
		                                .log())
		            .expectNext(6, 6)
		            .verifyComplete();

		Hooks.resetOnEachOperator();
	}

}
