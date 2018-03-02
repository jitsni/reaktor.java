/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.reaktor.internal.acceptor;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.agrona.LangUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.reaktivity.nukleus.Nukleus;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteKind;
import org.reaktivity.nukleus.stream.StreamFactoryBuilder;
import org.reaktivity.reaktor.internal.Context;
import org.reaktivity.reaktor.internal.acceptable.Acceptable;
import org.reaktivity.reaktor.internal.conductor.Conductor;
import org.reaktivity.reaktor.internal.router.ReferenceKind;
import org.reaktivity.reaktor.internal.router.Router;
import org.reaktivity.reaktor.internal.types.OctetsFW;
import org.reaktivity.reaktor.internal.types.StringFW;
import org.reaktivity.reaktor.internal.types.control.Role;
import org.reaktivity.reaktor.internal.types.control.RouteFW;
import org.reaktivity.reaktor.internal.types.control.UnrouteFW;

public final class Acceptor extends Nukleus.Composite
{
    private static final Pattern SOURCE_NAME = Pattern.compile("([^#]+).*");

    private final RouteFW.Builder routeRW = new RouteFW.Builder();

    private final Context context;
    private final Map<String, Acceptable> acceptables;
    private final AtomicCounter routeRefs;
    private final MutableDirectBuffer routeBuf;
    private GroupBudgetManager groupBudgetManager;

    private Conductor conductor;
    private Router router;
    private Supplier<BufferPool> supplyBufferPool;
    private LongSupplier supplyGroupId;
    private Function<RouteKind, StreamFactoryBuilder> supplyStreamFactoryBuilder;
    private int abortTypeId;
    private Function<Role, MessagePredicate> supplyRouteHandler;
    private Predicate<RouteKind> allowZeroRouteRef;
    private AtomicLong correlations;
    private AtomicLong groups;

    public Acceptor(
        Context context)
    {
        this.context = context;
        this.routeRefs = context.counters().routes();
        this.acceptables = new HashMap<>();
        this.routeBuf = new UnsafeBuffer(ByteBuffer.allocateDirect(context.maxControlCommandLength()));
        this.correlations  = new AtomicLong();
        this.groups = new AtomicLong();
        this.supplyGroupId = () -> groups.incrementAndGet();
    }

    public void setConductor(
        Conductor conductor)
    {
        this.conductor = conductor;
    }

    public void setRouter(
        Router router)
    {
        this.router = router;
    }

    public void setBufferPoolSupplier(
        Supplier<BufferPool> supplyBufferPool)
    {
        this.supplyBufferPool = supplyBufferPool;
    }

    public void setGroupBudgetManager(
        GroupBudgetManager groupBudgetManager)
    {
        this.groupBudgetManager = groupBudgetManager;
    }

    public void setStreamFactoryBuilderSupplier(
        Function<RouteKind, StreamFactoryBuilder> supplyStreamFactoryBuilder)
    {
        this.supplyStreamFactoryBuilder = supplyStreamFactoryBuilder;
    }

    public void setAbortTypeId(
        int abortTypeId)
    {
        this.abortTypeId = abortTypeId;
    }

    public void setRouteHandlerSupplier(
        Function<Role, MessagePredicate> supplyRouteHandler)
    {
        this.supplyRouteHandler = supplyRouteHandler;
    }

    public void setAllowZeroRouteRef(
        Predicate<RouteKind> allowZeroRouteRef)
    {
        this.allowZeroRouteRef = allowZeroRouteRef;
    }

    @Override
    public String name()
    {
        return "acceptor";
    }

    public void doRoute(
        RouteFW route)
    {
        final String sourceName = route.source().asString();

        acceptables.computeIfAbsent(sourceName, this::newAcceptable);

        try
        {
            Role role = route.role().get();
            MessagePredicate routeHandler = supplyRouteHandler.apply(role);
            if (!allowZeroRouteRef.test(RouteKind.valueOf(role.ordinal())))
            {
                route = generateSourceRefIfNecessary(route);
                final long sourceRef = route.sourceRef();
                MessagePredicate defaultHandler = (t, b, i, l) -> ReferenceKind.resolve(sourceRef).ordinal() == role.ordinal();
                if (routeHandler == null)
                {
                    routeHandler = defaultHandler;
                }
                else
                {
                    routeHandler = defaultHandler.and(routeHandler);
                }
            }
            if (routeHandler == null)
            {
                routeHandler = (t, b, i, l) -> true;
            }

            if (router.doRoute(route, routeHandler))
            {
                conductor.onRouted(route.correlationId(), route.sourceRef());
            }
            else
            {
                conductor.onError(route.correlationId());
            }
        }
        catch (Exception ex)
        {
            conductor.onError(route.correlationId());
            LangUtil.rethrowUnchecked(ex);
        }
    }

    public void doUnroute(
        UnrouteFW unroute)
    {
        final String sourceName = unroute.source().asString();
        final long correlationId = unroute.correlationId();

        final Acceptable acceptable = acceptables.get(sourceName);
        if (acceptable != null)
        {
            try
            {
                Role role = unroute.role().get();
                MessagePredicate routeHandler = supplyRouteHandler.apply(role);
                if (routeHandler == null)
                {
                    routeHandler = (t, b, i, l) -> true;
                }

                if (router.doUnroute(unroute, routeHandler))
                {
                    conductor.onUnrouted(correlationId);
                }
                else
                {
                    conductor.onError(correlationId);
                }
            }
            catch (Exception ex)
            {
                conductor.onError(correlationId);
                LangUtil.rethrowUnchecked(ex);
            }
        }
        else
        {
            conductor.onError(correlationId);
        }
    }

    public void onReadable(
        Path sourcePath)
    {
        String sourceName = source(sourcePath);
        Acceptable acceptable = acceptables.computeIfAbsent(sourceName, this::newAcceptable);
        String partitionName = sourcePath.getFileName().toString();
        acceptable.onReadable(partitionName);
    }

    public void onExpired(
        Path sourcePath)
    {
        // TODO:
    }

    private static String source(
        Path path)
    {
        Matcher matcher = SOURCE_NAME.matcher(path.getName(path.getNameCount() - 1).toString());
        if (matcher.matches())
        {
            return matcher.group(1);
        }
        else
        {
            throw new IllegalStateException();
        }
    }

    private Acceptable newAcceptable(
        String sourceName)
    {
        return include(new Acceptable(
                context,
                router,
                sourceName,
                supplyGroupId,
                groupBudgetManager::claim,
                groupBudgetManager::release,
                supplyBufferPool,
                supplyStreamFactoryBuilder,
                abortTypeId,
                correlations));
    }

    private RouteFW generateSourceRefIfNecessary(
        RouteFW route)
    {
        if (route.sourceRef() == 0L)
        {
            final Role role = route.role().get();
            final ReferenceKind routeKind = ReferenceKind.valueOf(role);
            final long newSourceRef = routeKind.nextRef(routeRefs);
            final StringFW source = route.source();
            final StringFW target = route.target();
            final long targetRef = route.targetRef();
            final long authorization = route.authorization();
            final OctetsFW extension = route.extension();

            route = routeRW.wrap(routeBuf, 0, routeBuf.capacity())
                           .correlationId(route.correlationId())
                           .role(b -> b.set(role))
                           .source(source)
                           .sourceRef(newSourceRef)
                           .target(target)
                           .targetRef(targetRef)
                           .authorization(authorization)
                           .extension(b -> b.set(extension))
                           .build();
        }

        return route;
    }
}
