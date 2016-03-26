package me.cybermaxke.chunkpregenutil;

import static org.spongepowered.api.util.SpongeApiTranslationHelper.t;

import com.flowpowered.math.GenericMath;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.ArgumentParseException;
import org.spongepowered.api.command.args.CommandArgs;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.CommandElement;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.source.LocatedSource;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.util.annotation.NonnullByDefault;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.WorldBorder;
import org.spongepowered.api.world.storage.WorldProperties;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

@NonnullByDefault
@Plugin(id = "cybermaxke.chunkpregenutil", name = "ChunkPreGenUtil", version = "1.0.0-SNAPSHOT")
public class ChunkPreGenUtil {

    private final static String ARG_WORLD = "world";
    private final static String ARG_TICK_INTERVAL = "tickInterval";
    private final static String ARG_CHUNKS_PER_TICK = "chunksPerTick";
    private final static String ARG_TICK_PERCENT_LIMIT = "tickPercentLimit";

    private final Map<UUID, Task> runningTasks = new WeakHashMap<>();

    @Inject
    private Logger logger;

    @Inject
    private Game game;

    @Listener
    public void onInit(GameInitializationEvent event) {
        this.game.getCommandManager().register(this, CommandSpec.builder()
                .permission("chunkpregenutil.command")
                .child(CommandSpec.builder()
                        .permission("chunkpregenutil.command.start")
                        .arguments(
                                GenericArguments.optional(GenericArguments.world(Text.of(ARG_WORLD))),
                                GenericArguments.flags()
                                        .flag("-log", "-l", "l")
                                        .valueFlag(new BoundedNumberCommandElement<>(GenericArguments.integer(Text.of(ARG_TICK_INTERVAL)), null, 1),
                                                "-tickInterval", "-ti")
                                        .valueFlag(new BoundedNumberCommandElement<>(GenericArguments.integer(Text.of(ARG_CHUNKS_PER_TICK)), null, 1),
                                                "-chunksPerTick", "-cpt")
                                        .valueFlag(new BoundedNumberCommandElement<>(GenericArguments.doubleNum(Text.of(ARG_TICK_PERCENT_LIMIT)),
                                                1.0, GenericMath.DBL_EPSILON), "-tickPercentLimit", "-tpl")
                                        .buildWith(GenericArguments.none()))
                        .executor(new WorldCommandExecutor() {
                            @Override
                            public CommandResult execute(CommandSource src, CommandContext args, World world) throws CommandException {
                                Task oldTask = runningTasks.get(world.getUniqueId());
                                if (oldTask != null) {
                                    if (game.getScheduler().getScheduledTasks(this).contains(oldTask)) {
                                        throw new CommandException(
                                                Text.of("There is already a chunk pre generate task running, please wait for it to finish."));
                                    }
                                    runningTasks.remove(world.getUniqueId());
                                }
                                WorldBorder.ChunkPreGenerate chunkPreGenerate = world.getWorldBorder().newChunkPreGenerate(world);
                                chunkPreGenerate.owner(ChunkPreGenUtil.this);
                                args.<Integer>getOne(ARG_TICK_INTERVAL).ifPresent(chunkPreGenerate::tickInterval);
                                args.<Integer>getOne(ARG_CHUNKS_PER_TICK).ifPresent(chunkPreGenerate::chunksPerTick);
                                args.<Double>getOne(ARG_TICK_PERCENT_LIMIT).ifPresent(v -> chunkPreGenerate.tickPercentLimit(v.floatValue()));
                                if (args.hasAny("log")) {
                                    chunkPreGenerate.logger(logger);
                                }
                                Task task = chunkPreGenerate.start();
                                runningTasks.put(world.getUniqueId(), task);
                                src.sendMessage(Text.of("Successfully started the chunk pre generation in the world " + world.getName() + "."));
                                return CommandResult.success();
                            }
                        })
                        .build(), "start")
                .child(CommandSpec.builder()
                        .permission("chunkpregenutil.command.stop")
                        .arguments(
                                GenericArguments.optional(GenericArguments.world(Text.of(ARG_WORLD))))
                        .executor(new WorldCommandExecutor() {
                            @Override
                            public CommandResult execute(CommandSource src, CommandContext args, World world) throws CommandException {
                                Task task = runningTasks.remove(world.getUniqueId());
                                if (task == null || task.cancel()) {
                                    src.sendMessage(Text.of("There is no chunk pre generation task running for the world " + world.getName() + "."));
                                } else {
                                    src.sendMessage(Text.of("Successfully cancelled the chunk pre generation task running for the world " +
                                            world.getName() + "."));
                                }
                                return CommandResult.success();
                            }
                        })
                        .build(), "stop")
                .build(), Lists.newArrayList("chunkpregen", "cpg"));
    }

    private static Method parseValueMethod;

    static {
        try {
            parseValueMethod = CommandElement.class.getDeclaredMethod("parseValue", CommandSource.class, CommandArgs.class);
            parseValueMethod.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private class BoundedNumberCommandElement<T extends Number> extends CommandElement {

        private final CommandElement delegate;
        @Nullable private final T upperBound;
        @Nullable private final T lowerBound;

        protected BoundedNumberCommandElement(CommandElement delegate, @Nullable T upperBound, @Nullable T lowerBound) {
            super(delegate.getKey());
            this.delegate = delegate;
            this.upperBound = upperBound;
            this.lowerBound = lowerBound;
        }

        @SuppressWarnings("unchecked")
        @Nullable
        @Override
        protected Object parseValue(CommandSource source, CommandArgs args) throws ArgumentParseException {
            try {
                // I wish that the method was exposed...
                T value = (T) parseValueMethod.invoke(this.delegate, source, args);
                if (this.upperBound != null && value.doubleValue() > this.upperBound.doubleValue()) {
                    throw args.createError(t("The value (%s) exceeded the upper bound (%s).", value, this.upperBound));
                }
                if (this.lowerBound != null && value.doubleValue() < this.lowerBound.doubleValue()) {
                    throw args.createError(t("The value (%s) exceeded the lower bound (%s).", value, this.lowerBound));
                }
                return value;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public List<String> complete(CommandSource src, CommandArgs args, CommandContext context) {
            return this.delegate.complete(src, args, context);
        }
    }

    private abstract class WorldCommandExecutor implements CommandExecutor {

        @Override
        public CommandResult execute(CommandSource src, CommandContext args) throws CommandException {
            World world;
            if (args.hasAny(ARG_WORLD)) {
                WorldProperties worldProperties = args.<WorldProperties>getOne(ARG_WORLD).get();
                world = game.getServer().getWorld(worldProperties.getUniqueId())
                        .orElseThrow(() -> new CommandException(Text.of("The target world must be loaded.")));
            } else if (src instanceof LocatedSource) {
                world = ((LocatedSource) src).getWorld();
            } else {
                throw new CommandException(Text.of("Every non-located source must specify a target world."));
            }
            return this.execute(src, args, world);
        }

        protected abstract CommandResult execute(CommandSource src, CommandContext args, World world) throws CommandException;
    }
}
