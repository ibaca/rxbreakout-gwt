package breakout.client;

import static breakout.client.RxElemental2.keydown;
import static elemental2.dom.DomGlobal.document;
import static java.lang.Double.NaN;
import static rx.Observable.empty;
import static rx.Observable.just;

import com.google.gwt.core.client.EntryPoint;
import elemental2.core.Date;
import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.CanvasRenderingContext2D.FillStyleUnionType;
import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.HTMLPreElement;
import elemental2.media.AudioBufferSourceNode;
import elemental2.media.AudioContext;
import elemental2.media.OscillatorNode;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jsinterop.base.Js;
import rx.Observable;
import rx.functions.Func1;
import rx.subjects.PublishSubject;

/**
 * Another remake of https://en.wikipedia.org/wiki/Breakout_(video_game)
 * Using Rx based on https://github.com/Lorti/rxjs-breakout
 * Style from https://github.com/staltz/flux-challenge
 */
public class Breakout implements EntryPoint {

    @Override public void onModuleLoad() {

        // Graphics

        HTMLCanvasElement canvas = Js.cast(document.getElementById("stage"));
        CanvasRenderingContext2D context = Js.cast(canvas.getContext("2d"));
        HTMLPreElement debug = Js.cast(document.getElementById("state"));

        context.fillStyle = FillStyleUnionType.of("pink");
        int PADDLE_WIDTH = 100;
        int PADDLE_HEIGHT = 20;

        int BALL_RADIUS = 10;

        int BRICK_ROWS = 5;
        int BRICK_COLUMNS = 7;
        int BRICK_HEIGHT = 20;
        int BRICK_GAP = 3;

        Runnable drawTitle = () -> {
            context.textAlign = "center";
            context.font = "24px Courier New";
            context.fillText("Breakout (RxJava + GWT)", canvas.width / 2, canvas.height / 2 - 24);
        };

        Runnable drawControls = () -> {
            context.textAlign = "center";
            context.font = "16px Courier New";
            context.fillText("press [<] and [>] to play", canvas.width / 2, canvas.height / 2);
        };

        Consumer<String> drawGameOver = text -> {
            context.clearRect(canvas.width / 4, canvas.height / 3, canvas.width / 2, canvas.height / 3);
            context.textAlign = "center";
            context.font = "24px Courier New";
            context.fillText(text, canvas.width / 2, canvas.height / 2);
            context.font = "16px Courier New";
            context.fillText("press [space] to play again", canvas.width / 2, canvas.height / 2 + 24);
        };

        Runnable drawAuthor = () -> {
            context.textAlign = "center";
            context.font = "14px Courier New";
            context.fillText("GWT version by Ignacio Baca", canvas.width / 2, canvas.height / 2 + 24);
            context.fillText("JS version by Manuel Wieser", canvas.width / 2, canvas.height / 2 + 40);
        };

        Consumer<Integer> drawScore = score -> {
            context.textAlign = "left";
            context.font = "16px Courier New";
            context.fillText(Integer.toString(score), BRICK_GAP, 16);
        };

        Consumer<Double> drawPaddle = position -> {
            context.beginPath();
            context.rect(
                    position - PADDLE_WIDTH / 2,
                    context.canvas.height - PADDLE_HEIGHT,
                    PADDLE_WIDTH,
                    PADDLE_HEIGHT
            );
            context.fill();
            context.closePath();
        };

        Consumer<String> drawState = text -> debug.textContent = text;

        class Position {
            double x, y;
            @Override public String toString() {
                return "{x=" + (int) x + ", y=" + (int) y + '}';
            }
        }

        class Ball {
            Position position = new Position();
            Position direction = new Position();
            @Override public String toString() {
                return "Ball{position=" + position + ", direction=" + direction + '}';
            }
        }

        Consumer<Ball> drawBall = ball -> {
            context.beginPath();
            context.arc(ball.position.x, ball.position.y, BALL_RADIUS, 0, Math.PI * 2);
            context.fill();
            context.closePath();
        };

        class Brick {
            double x, y, width, height;
            @Override public String toString() {
                return "Brick{x=" + x + ", y=" + y + ", width=" + width + ", height=" + height + '}';
            }
        }

        Consumer<Brick> drawBrick = brick -> {
            context.beginPath();
            context.rect(
                    brick.x - brick.width / 2,
                    brick.y - brick.height / 2,
                    brick.width,
                    brick.height
            );
            context.fill();
            context.closePath();
        };

        Consumer<Brick[]> drawBricks = bricks -> {
            for (Brick brick : bricks) drawBrick.accept(brick);
        };

        // Sounds

        AudioContext audio = new AudioContext();
        PublishSubject<Integer> beeper = PublishSubject.create();
        beeper.sample(100, TimeUnit.MILLISECONDS).subscribe(key -> {

            OscillatorNode oscillator = audio.createOscillator();
            oscillator.connect(audio.destination);
            oscillator.type = "square";

            // https://en.wikipedia.org/wiki/Piano_key_frequencies
            oscillator.frequency.value = Math.pow(2, (key - 49) / 12) * 440;

            //XXX OscillatorNode should implements AudioScheduledSourceNode but it doesn't exists!
            //XXX buffered contains the start/stop methods, but requires unchecked cast
            AudioBufferSourceNode playable = Js.uncheckedCast(oscillator);
            playable.start(0);
            playable.stop(audio.currentTime + 0.100);
        });

        // Ticker

        int TICKER_INTERVAL = 17;

        class Tick {
            double time; // millis
            double delta; // seconds
            public Tick(double time, double delta) {
                this.time = time;
                this.delta = delta;
            }
            @Override public String toString() { return "Tick{time=" + time + ", deltaTime=" + delta + '}'; }
        }

        Observable<Tick> ticker$ = Observable
                .interval(TICKER_INTERVAL, TimeUnit.MILLISECONDS) //TODO Rx.Scheduler.requestAnimationFrame
                .map(n -> new Tick(Date.now(), NaN))
                .scan((previous, current) -> new Tick(current.time, (current.time - previous.time) / 1000))
                .skip(1/*ignore initial value*/);

        // Paddle

        int PADDLE_SPEED = 240;

        Function<String, Observable<Integer>> untilUp = code -> RxElemental2.fromEvent(document, RxElemental2.keyup)
                .filter(up -> Objects.equals(up.code, code))
                .map(up -> 0);

        Observable<Integer> input$ =
                RxElemental2.fromEvent(document, keydown).switchMap(down -> {
                    switch (down.code) {
                        case "ArrowLeft": return just(-1).concatWith(untilUp.apply(down.code));
                        case "ArrowRight": return just(1).concatWith(untilUp.apply(down.code));
                        default: return empty();
                    }
                }).distinctUntilChanged();

        Observable<Double> paddle$ = ticker$
                .withLatestFrom(input$, (ticker, direction) -> direction * ticker.delta * PADDLE_SPEED)
                .scan(canvas.width / 2, (position, move) -> {
                    return Math.max(Math.min(position + move, canvas.width - PADDLE_WIDTH / 2), PADDLE_WIDTH / 2);
                }).skip(1/*ignore initial value*/)
                .distinctUntilChanged();

        // Bricks

        Supplier<Brick[]> factory = () -> {
            double width = (canvas.width - BRICK_GAP - BRICK_GAP * BRICK_COLUMNS) / BRICK_COLUMNS;
            return IntStream.range(0, BRICK_ROWS).boxed().flatMap(i -> {
                return IntStream.range(0, BRICK_COLUMNS).mapToObj(j -> {
                    Brick brick = new Brick();
                    brick.x = j * (width + BRICK_GAP) + width / 2 + BRICK_GAP;
                    brick.y = i * (BRICK_HEIGHT + BRICK_GAP) + BRICK_HEIGHT / 2 + BRICK_GAP + 20;
                    brick.width = width;
                    brick.height = BRICK_HEIGHT;
                    return brick;
                });
            }).toArray(Brick[]::new);
        };

        BiFunction<Brick, Ball, Boolean> collision = (brick, ball) -> {
            return ball.position.x + ball.direction.x > brick.x - brick.width / 2
                    && ball.position.x + ball.direction.x < brick.x + brick.width / 2
                    && ball.position.y + ball.direction.y > brick.y - brick.height / 2
                    && ball.position.y + ball.direction.y < brick.y + brick.height / 2;
        };

        // Ball

        int BALL_SPEED = 60;

        class Collisions {
            boolean paddle;
            boolean wall;
            boolean ceiling;
            boolean brick;
            @Override public String toString() {
                return "Collisions{" +
                        "paddle=" + paddle +
                        ", wall=" + wall +
                        ", ceiling=" + ceiling +
                        ", brick=" + brick +
                        '}';
            }
        }

        class State {
            Tick tick;
            double paddle;
            Ball ball = new Ball();
            Brick[] bricks;
            Collisions collisions = new Collisions();
            int score;
            @Override public String toString() {
                return "State{" +
                        "\n  tick=" + tick +
                        ", \n  paddle=" + paddle +
                        ", \n  ball=" + ball +
                        ", \n  collisions=" + collisions +
                        ", \n  score=" + score +
                        "\n}";
            }
        }

        State INITIAL_OBJECTS = new State();
        INITIAL_OBJECTS.ball.position.x = canvas.width / 2;
        INITIAL_OBJECTS.ball.position.y = canvas.height / 2;
        INITIAL_OBJECTS.ball.direction.x = 2;
        INITIAL_OBJECTS.ball.direction.y = 2;
        INITIAL_OBJECTS.bricks = factory.get();

        class TickerPaddle {
            final Tick ticker;
            final double paddle;
            TickerPaddle(Tick ticker, Double paddle) { this.ticker = ticker; this.paddle = paddle; }
        }

        Observable<State> state$ = ticker$
                .withLatestFrom(paddle$, (ticker, paddle) -> new TickerPaddle(ticker, paddle))
                .scan(INITIAL_OBJECTS, (state, tickerPaddle) -> {
                    final State out = new State();
                    out.tick = tickerPaddle.ticker;
                    out.paddle = tickerPaddle.paddle;
                    out.score = state.score;

                    final Ball ball = state.ball;
                    out.ball.position.x = ball.position.x + ball.direction.x * tickerPaddle.ticker.delta * BALL_SPEED;
                    out.ball.position.y = ball.position.y + ball.direction.y * tickerPaddle.ticker.delta * BALL_SPEED;
                    out.ball.direction.x = ball.direction.x;
                    out.ball.direction.y = ball.direction.y;

                    out.collisions = new Collisions();
                    out.bricks = Stream.of(state.bricks).filter(brick -> {
                        if (!collision.apply(brick, out.ball)) {
                            return true;
                        } else {
                            out.ball.direction.y *= -1;
                            out.collisions.brick = true;
                            out.score = out.score + 10;
                            return false;
                        }
                    }).toArray(Brick[]::new);

                    double paddleTop = canvas.height - PADDLE_HEIGHT - BALL_RADIUS / 2;
                    if (out.ball.position.x > tickerPaddle.paddle - PADDLE_WIDTH / 2
                            && out.ball.position.x < tickerPaddle.paddle + PADDLE_WIDTH / 2
                            && out.ball.position.y > paddleTop) {
                        out.ball.position.y = paddleTop;
                        out.ball.direction.y *= -1;
                        out.collisions.paddle = true;
                    }

                    boolean leftWall = out.ball.position.x < BALL_RADIUS;
                    if (leftWall || out.ball.position.x > canvas.width - BALL_RADIUS) {
                        out.ball.position.x = leftWall ? BALL_RADIUS : canvas.width - BALL_RADIUS;
                        out.ball.direction.x *= -1;
                        out.collisions.wall = true;
                    }

                    if (out.ball.position.y < BALL_RADIUS) {
                        out.ball.position.y = BALL_RADIUS;
                        out.ball.direction.y *= -1;
                        out.collisions.ceiling = true;
                    }

                    return out;
                }).skip(1/*ignore initial value*/);

        // Game

        Func1<State, Boolean> update = game -> {
            context.clearRect(0, 0, canvas.width, canvas.height);

            drawPaddle.accept(game.paddle);
            drawBall.accept(game.ball);
            drawBricks.accept(game.bricks);
            drawScore.accept(game.score);
            drawState.accept(game.toString());

            if (game.ball.position.y > canvas.height - BALL_RADIUS) {
                beeper.onNext(28);
                drawGameOver.accept("GAME OVER");
                return true;
            }

            if (game.bricks.length == 0) {
                beeper.onNext(52);
                drawGameOver.accept("CONGRATULATIONS");
                return true;
            }

            if (game.collisions.paddle) beeper.onNext(40);
            if (game.collisions.wall || game.collisions.ceiling) beeper.onNext(45);
            if (game.collisions.brick) beeper.onNext(47 + (int) Math.floor(game.ball.position.y % 12));
            return false;
        };

        Observable<State> game$ = Observable.defer(() -> {
            context.clearRect(0, 0, canvas.width, canvas.height);
            drawTitle.run();
            drawControls.run();
            drawAuthor.run();

            // the 'backpressureLatest' assert that the 'sample' do not sent any last event after unsubscription
            return state$.sample(TICKER_INTERVAL, TimeUnit.MILLISECONDS).onBackpressureLatest().takeUntil(update);
        });

        RxElemental2.fromEvent(document, keydown).map(event -> event.code)
                .filter(code -> Objects.equals(code, "Space"))
                .startWith("Loaded")
                .switchMap(n -> game$)
                .subscribe();
    }
}
