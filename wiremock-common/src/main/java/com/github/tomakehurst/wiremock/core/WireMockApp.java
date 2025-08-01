/*
 * Copyright (C) 2012-2025 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock.core;

import static com.github.tomakehurst.wiremock.common.ParameterUtils.getFirstNonNull;

import com.github.tomakehurst.wiremock.admin.AdminRoutes;
import com.github.tomakehurst.wiremock.admin.LimitAndOffsetPaginator;
import com.github.tomakehurst.wiremock.admin.model.*;
import com.github.tomakehurst.wiremock.common.BrowserProxySettings;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.xml.Xml;
import com.github.tomakehurst.wiremock.extension.*;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilter;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterV2;
import com.github.tomakehurst.wiremock.global.GlobalSettings;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.http.client.HttpClient;
import com.github.tomakehurst.wiremock.matching.RequestMatcherExtension;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.recording.*;
import com.github.tomakehurst.wiremock.standalone.MappingsLoader;
import com.github.tomakehurst.wiremock.store.DefaultStores;
import com.github.tomakehurst.wiremock.store.SettingsStore;
import com.github.tomakehurst.wiremock.store.Stores;
import com.github.tomakehurst.wiremock.stubbing.*;
import com.github.tomakehurst.wiremock.verification.*;
import com.jayway.jsonpath.JsonPathException;
import com.jayway.jsonpath.spi.cache.CacheProvider;
import com.jayway.jsonpath.spi.cache.NOOPCache;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class WireMockApp implements StubServer, Admin {

  public static final String FILES_ROOT = "__files";
  public static final String ADMIN_CONTEXT_ROOT = "/__admin";
  public static final String MAPPINGS_ROOT = "mappings";
  private static final AtomicBoolean FACTORIES_LOADING_OPTIMIZED = new AtomicBoolean(false);

  private final Stores stores;
  private final Scenarios scenarios;
  private final StubMappings stubMappings;
  private final RequestJournal requestJournal;
  private final SettingsStore settingsStore;
  private final boolean browserProxyingEnabled;
  private final MappingsLoader defaultMappingsLoader;
  private final Container container;
  private final MappingsSaver mappingsSaver;
  private final NearMissCalculator nearMissCalculator;
  private final Recorder recorder;
  private final List<GlobalSettingsListener> globalSettingsListeners;
  private final Map<String, MappingsLoaderExtension> mappingsLoaderExtensions;
  private final Map<String, ServeEventListener> serveEventListeners;

  private Options options;

  private Extensions extensions;

  public WireMockApp(Options options, Container container) {
    if (!options.getDisableOptimizeXmlFactoriesLoading()
        && Boolean.FALSE.equals(FACTORIES_LOADING_OPTIMIZED.get())) {
      Xml.optimizeFactoriesLoading();
      FACTORIES_LOADING_OPTIMIZED.set(true);
    }

    try {
      // Disabling JsonPath's cache due to
      // https://github.com/json-path/JsonPath/issues/975#issuecomment-1867293053 and the fact that
      // we're now doing our own caching.
      CacheProvider.setCache(new NOOPCache());
    } catch (JsonPathException ignored) {
      // May fail on subsequent runs, but this doesn't matter
    }

    this.options = options;
    this.stores = options.getStores();
    this.stores.start();

    this.browserProxyingEnabled = options.browserProxySettings().enabled();
    this.defaultMappingsLoader = options.mappingsLoader();
    this.mappingsSaver = options.mappingsSaver();

    this.settingsStore = stores.getSettingsStore();

    extensions =
        new Extensions(
            options.getDeclaredExtensions(),
            this,
            options,
            stores,
            options.filesRoot().child(FILES_ROOT));
    extensions.load();

    Map<String, RequestMatcherExtension> customMatchers =
        extensions.ofType(RequestMatcherExtension.class);

    serveEventListeners = extensions.ofType(ServeEventListener.class);

    requestJournal =
        options.requestJournalDisabled()
            ? new DisabledRequestJournal()
            : new StoreBackedRequestJournal(
                options.maxRequestJournalEntries().orElse(null),
                customMatchers,
                stores.getRequestJournalStore());

    scenarios = new InMemoryScenarios(stores.getScenariosStore());
    stubMappings =
        new StoreBackedStubMappings(
            stores.getStubStore(),
            scenarios,
            customMatchers,
            extensions.ofType(ResponseDefinitionTransformer.class),
            extensions.ofType(ResponseDefinitionTransformerV2.class),
            stores.getFilesBlobStore(),
            List.copyOf(extensions.ofType(StubLifecycleListener.class).values()),
            serveEventListeners);
    nearMissCalculator =
        new NearMissCalculator(stubMappings, requestJournal, scenarios, customMatchers);
    recorder =
        new Recorder(this, extensions, stores.getFilesBlobStore(), stores.getRecorderStateStore());
    globalSettingsListeners = List.copyOf(extensions.ofType(GlobalSettingsListener.class).values());
    this.mappingsLoaderExtensions = extensions.ofType(MappingsLoaderExtension.class);

    this.container = container;
    extensions.startAll();
    loadDefaultMappings();
  }

  public WireMockApp(
      boolean browserProxyingEnabled,
      MappingsLoader defaultMappingsLoader,
      Map<String, MappingsLoaderExtension> mappingsLoaderExtensions,
      MappingsSaver mappingsSaver,
      boolean requestJournalDisabled,
      Integer maxRequestJournalEntries,
      Map<String, ResponseDefinitionTransformer> transformers,
      Map<String, ResponseDefinitionTransformerV2> v2transformers,
      Map<String, RequestMatcherExtension> requestMatchers,
      FileSource rootFileSource,
      Container container) {

    this.stores = new DefaultStores(rootFileSource);

    this.browserProxyingEnabled = browserProxyingEnabled;
    this.defaultMappingsLoader = defaultMappingsLoader;
    this.mappingsLoaderExtensions = mappingsLoaderExtensions;
    this.mappingsSaver = mappingsSaver;
    this.settingsStore = stores.getSettingsStore();
    requestJournal =
        requestJournalDisabled
            ? new DisabledRequestJournal()
            : new StoreBackedRequestJournal(
                maxRequestJournalEntries, requestMatchers, stores.getRequestJournalStore());
    scenarios = new InMemoryScenarios(stores.getScenariosStore());

    serveEventListeners = Collections.emptyMap();

    stubMappings =
        new StoreBackedStubMappings(
            stores.getStubStore(),
            scenarios,
            requestMatchers,
            transformers,
            v2transformers,
            stores.getFilesBlobStore(),
            Collections.emptyList(),
            serveEventListeners);
    this.container = container;
    nearMissCalculator =
        new NearMissCalculator(stubMappings, requestJournal, scenarios, requestMatchers);
    recorder =
        new Recorder(this, extensions, stores.getFilesBlobStore(), stores.getRecorderStateStore());
    globalSettingsListeners = Collections.emptyList();
    loadDefaultMappings();
  }

  public AdminRequestHandler buildAdminRequestHandler() {
    AdminRoutes adminRoutes =
        AdminRoutes.forServer(extensions.ofType(AdminApiExtension.class).values(), stores);
    return new AdminRequestHandler(
        adminRoutes,
        this,
        new BasicResponseRenderer(),
        options.getAdminAuthenticator(),
        options.getHttpsRequiredForAdminApi(),
        getAdminRequestFilters(),
        getV2AdminRequestFilters(),
        options.getDataTruncationSettings());
  }

  public StubRequestHandler buildStubRequestHandler() {
    Map<String, PostServeAction> postServeActions = extensions.ofType(PostServeAction.class);
    BrowserProxySettings browserProxySettings = options.browserProxySettings();

    final com.github.tomakehurst.wiremock.http.client.HttpClientFactory httpClientFactory =
        extensions
            .ofType(com.github.tomakehurst.wiremock.http.client.HttpClientFactory.class)
            .values()
            .stream()
            .findFirst()
            .orElse(options.httpClientFactory());

    final HttpClient reverseProxyClient =
        httpClientFactory.buildHttpClient(options, true, Collections.emptyList(), true);
    final HttpClient forwardProxyClient =
        httpClientFactory.buildHttpClient(
            options,
            browserProxySettings.trustAllProxyTargets(),
            browserProxySettings.trustAllProxyTargets()
                ? Collections.emptyList()
                : browserProxySettings.trustedProxyTargets(),
            false);

    return new StubRequestHandler(
        this,
        new StubResponseRenderer(
            options.getStores().getFilesBlobStore(),
            settingsStore,
            new ProxyResponseRenderer(
                options.shouldPreserveHostHeader(),
                options.proxyHostHeader(),
                settingsStore,
                options.getStubCorsEnabled(),
                options.getSupportedProxyEncodings(),
                reverseProxyClient,
                forwardProxyClient),
            List.copyOf(extensions.ofType(ResponseTransformer.class).values()),
            List.copyOf(extensions.ofType(ResponseTransformerV2.class).values())),
        this,
        postServeActions,
        serveEventListeners,
        requestJournal,
        getStubRequestFilters(),
        getV2StubRequestFilters(),
        options.getStubRequestLoggingDisabled(),
        options.getDataTruncationSettings(),
        options.getNotMatchedRendererFactory().apply(extensions));
  }

  private List<RequestFilter> getAdminRequestFilters() {
    return extensions.ofType(RequestFilter.class).values().stream()
        .filter(RequestFilter::applyToAdmin)
        .collect(Collectors.toList());
  }

  private List<RequestFilterV2> getV2AdminRequestFilters() {
    return extensions.ofType(RequestFilterV2.class).values().stream()
        .filter(RequestFilterV2::applyToAdmin)
        .collect(Collectors.toList());
  }

  private List<RequestFilter> getStubRequestFilters() {
    return extensions.ofType(RequestFilter.class).values().stream()
        .filter(RequestFilter::applyToStubs)
        .collect(Collectors.toList());
  }

  private List<RequestFilterV2> getV2StubRequestFilters() {
    return extensions.ofType(RequestFilterV2.class).values().stream()
        .filter(RequestFilterV2::applyToStubs)
        .collect(Collectors.toList());
  }

  private void loadDefaultMappings() {
    loadMappingsUsing(defaultMappingsLoader);
    if (mappingsLoaderExtensions != null)
      mappingsLoaderExtensions.values().forEach(e -> loadMappingsUsing(e));
  }

  public void loadMappingsUsing(final MappingsLoader mappingsLoader) {
    mappingsLoader.loadMappingsInto(stubMappings);
  }

  @Override
  public ServeEvent serveStubFor(ServeEvent initialServeEvent) {
    ServeEvent serveEvent = stubMappings.serveFor(initialServeEvent);

    if (serveEvent.isNoExactMatch()
        && browserProxyingEnabled
        && serveEvent.getRequest().isBrowserProxyRequest()
        && getGlobalSettings().getSettings().getProxyPassThrough()) {
      return ServeEvent.ofUnmatched(
          serveEvent.getRequest(), ResponseDefinition.browserProxy(serveEvent.getRequest()));
    }

    return serveEvent;
  }

  @Override
  public void addStubMapping(StubMapping stubMapping) {
    addStubMapping(stubMapping, true);
  }

  /**
   * @param persistNow If true, will save persisted stubs. Otherwise, saving of stubs will be left
   *     to the caller.
   */
  private void addStubMapping(StubMapping stubMapping, boolean persistNow) {
    if (stubMapping.getId() == null) {
      stubMapping.setId(UUID.randomUUID());
    }

    stubMappings.addMapping(stubMapping);
    if (persistNow && stubMapping.shouldBePersisted()) {
      mappingsSaver.save(stubMapping);
    }
  }

  @Override
  public void removeStubMapping(StubMapping stubMapping) {
    removeStubMapping(stubMapping, true);
  }

  /**
   * @param persistNow If true, will save persisted stubs. Otherwise, saving of stubs will be left
   *     to the caller.
   */
  private void removeStubMapping(StubMapping stubMapping, boolean persistNow) {
    StubMapping matchedStub = findStubMatching(stubMapping);
    if (matchedStub == null) return;

    stubMappings.removeMapping(matchedStub);

    if (persistNow && matchedStub.shouldBePersisted()) {
      mappingsSaver.remove(matchedStub.getId());
    }
  }

  /**
   * Attempts to retrieve a stub mapping that matches the provided stub. For a stub to "match", it
   * must either share the same ID or the same request pattern. Matching the stub ID is prioritized
   * over matching the request pattern. In other words, stubs are only checked for matching request
   * patterns if no stubs are found that match the provided stub's ID.
   */
  private StubMapping findStubMatching(StubMapping stubMapping) {
    return stubMappings
        .get(stubMapping.getId())
        .orElseGet(
            () ->
                stubMappings.getAll().stream()
                    .filter(stub -> stub.getRequest().equals(stubMapping.getRequest()))
                    .findFirst()
                    .orElse(null));
  }

  @Override
  public void removeStubMapping(UUID id) {
    stubMappings.get(id).ifPresent(this::removeStubMapping);
  }

  @Override
  public void editStubMapping(StubMapping stubMapping) {
    editStubMapping(stubMapping, true);
  }

  /**
   * @param persistNow If true, will save persisted stubs. Otherwise, saving of stubs will be left
   *     to the caller.
   */
  private void editStubMapping(StubMapping stubMapping, boolean persistNow) {
    stubMappings.editMapping(stubMapping);
    if (persistNow && stubMapping.shouldBePersisted()) {
      mappingsSaver.save(stubMapping);
    }
  }

  @Override
  public ListStubMappingsResult listAllStubMappings() {
    return new ListStubMappingsResult(LimitAndOffsetPaginator.none(stubMappings.getAll()));
  }

  @Override
  public SingleStubMappingResult getStubMapping(UUID id) {
    return SingleStubMappingResult.fromOptional(stubMappings.get(id));
  }

  @Override
  public void saveMappings() {
    for (StubMapping stubMapping : stubMappings.getAll()) {
      stubMapping.setPersistent(true);
      stubMappings.editMapping(stubMapping);
    }
    mappingsSaver.save(stubMappings.getAll());
  }

  @Override
  public void resetAll() {
    resetToDefaultMappings();
  }

  @Override
  public void resetRequests() {
    requestJournal.reset();
  }

  @Override
  public void resetToDefaultMappings() {
    stubMappings.reset();
    resetRequests();
    loadDefaultMappings();
  }

  @Override
  public void resetScenarios() {
    stubMappings.resetScenarios();
  }

  @Override
  public void resetMappings() {
    mappingsSaver.removeAll();
    stubMappings.reset();
  }

  @Override
  public GetServeEventsResult getServeEvents() {
    return getServeEvents(ServeEventQuery.ALL);
  }

  @Override
  public GetServeEventsResult getServeEvents(ServeEventQuery query) {
    try {
      final List<ServeEvent> serveEvents = query.filter(requestJournal.getAllServeEvents());
      return GetServeEventsResult.requestJournalEnabled(LimitAndOffsetPaginator.none(serveEvents));
    } catch (RequestJournalDisabledException e) {
      return GetServeEventsResult.requestJournalDisabled(
          LimitAndOffsetPaginator.none(requestJournal.getAllServeEvents()));
    }
  }

  @Override
  public SingleServedStubResult getServedStub(UUID id) {
    return SingleServedStubResult.fromOptional(requestJournal.getServeEvent(id));
  }

  @Override
  public VerificationResult countRequestsMatching(RequestPattern requestPattern) {
    try {
      return VerificationResult.withCount(requestJournal.countRequestsMatching(requestPattern));
    } catch (RequestJournalDisabledException e) {
      return VerificationResult.withRequestJournalDisabled();
    }
  }

  @Override
  public FindRequestsResult findRequestsMatching(RequestPattern requestPattern) {
    try {
      List<LoggedRequest> requests = requestJournal.getRequestsMatching(requestPattern);
      return FindRequestsResult.withRequests(requests);
    } catch (RequestJournalDisabledException e) {
      return FindRequestsResult.withRequestJournalDisabled();
    }
  }

  @Override
  public FindRequestsResult findUnmatchedRequests() {
    try {
      List<LoggedRequest> requests =
          requestJournal.getAllServeEvents().stream()
              .filter(ServeEvent::isNoExactMatch)
              .map(ServeEvent::getRequest)
              .collect(Collectors.toList());
      return FindRequestsResult.withRequests(requests);
    } catch (RequestJournalDisabledException e) {
      return FindRequestsResult.withRequestJournalDisabled();
    }
  }

  @Override
  public void removeServeEvent(UUID eventId) {
    requestJournal.removeEvent(eventId);
  }

  @Override
  public FindServeEventsResult removeServeEventsMatching(RequestPattern requestPattern) {
    return new FindServeEventsResult(requestJournal.removeEventsMatching(requestPattern));
  }

  @Override
  public FindServeEventsResult removeServeEventsForStubsMatchingMetadata(
      StringValuePattern metadataPattern) {
    return new FindServeEventsResult(
        requestJournal.removeServeEventsForStubsMatchingMetadata(metadataPattern));
  }

  @Override
  public FindNearMissesResult findNearMissesForUnmatchedRequests() {
    List<NearMiss> nearMisses = new ArrayList<>();
    List<ServeEvent> unmatchedServeEvents =
        requestJournal.getAllServeEvents().stream()
            .filter(ServeEvent::isNoExactMatch)
            .collect(Collectors.toList());

    for (ServeEvent serveEvent : unmatchedServeEvents) {
      nearMisses.addAll(nearMissCalculator.findNearestTo(serveEvent.getRequest()));
    }

    return new FindNearMissesResult(nearMisses);
  }

  @Override
  public GetScenariosResult getAllScenarios() {
    return new GetScenariosResult(stubMappings.getAllScenarios());
  }

  @Override
  public void resetScenario(String name) {
    scenarios.resetSingle(name);
  }

  @Override
  public void setScenarioState(String name, String state) {
    scenarios.setSingle(name, state);
  }

  @Override
  public FindNearMissesResult findTopNearMissesFor(LoggedRequest loggedRequest) {
    return new FindNearMissesResult(nearMissCalculator.findNearestTo(loggedRequest));
  }

  @Override
  public FindNearMissesResult findTopNearMissesFor(RequestPattern requestPattern) {
    return new FindNearMissesResult(nearMissCalculator.findNearestTo(requestPattern));
  }

  @Override
  public GetGlobalSettingsResult getGlobalSettings() {
    return new GetGlobalSettingsResult(settingsStore.get());
  }

  @Override
  public void updateGlobalSettings(GlobalSettings newSettings) {
    GlobalSettings oldSettings = settingsStore.get();

    for (GlobalSettingsListener listener : globalSettingsListeners) {
      listener.beforeGlobalSettingsUpdated(oldSettings, newSettings);
    }

    settingsStore.set(newSettings);

    for (GlobalSettingsListener listener : globalSettingsListeners) {
      listener.afterGlobalSettingsUpdated(oldSettings, newSettings);
    }
  }

  public int port() {
    return container.port();
  }

  @Override
  public Options getOptions() {
    return options;
  }

  public Extensions getExtensions() {
    return extensions;
  }

  @Override
  public void shutdownServer() {
    extensions.stopAll();
    stores.stop();
    container.shutdown();
  }

  public SnapshotRecordResult snapshotRecord() {
    return snapshotRecord(RecordSpec.DEFAULTS);
  }

  @Override
  public SnapshotRecordResult snapshotRecord(RecordSpecBuilder spec) {
    return snapshotRecord(spec.build());
  }

  public SnapshotRecordResult snapshotRecord(RecordSpec recordSpec) {
    return recorder.takeSnapshot(getServeEvents().getServeEvents(), recordSpec);
  }

  @Override
  public void startRecording(String targetBaseUrl) {
    recorder.startRecording(RecordSpec.forBaseUrl(targetBaseUrl));
  }

  @Override
  public void startRecording(RecordSpec recordSpec) {
    recorder.startRecording(recordSpec);
  }

  @Override
  public void startRecording(RecordSpecBuilder recordSpec) {
    recorder.startRecording(recordSpec.build());
  }

  @Override
  public SnapshotRecordResult stopRecording() {
    return recorder.stopRecording();
  }

  @Override
  public RecordingStatusResult getRecordingStatus() {
    return new RecordingStatusResult(recorder.getStatus().name());
  }

  private Set<UUID> findMatchedStubIds() {
    return requestJournal.getAllServeEvents().stream()
        .filter(event -> event.getStubMapping() != null)
        .map(event -> event.getStubMapping().getId())
        .collect(Collectors.toSet());
  }

  @Override
  public ListStubMappingsResult findUnmatchedStubs() {
    // Collect IDs of stub mappings that have matched at least one request in a HashSet for O(1)
    // lookups so this method is O(n + m), where n is the number of stubs and m is the number of
    // requests in the journal.
    // It'd be slightly more efficient to use IdentityHashMap, but that's error-prone.
    Set<UUID> servedStubIds = findMatchedStubIds();
    List<StubMapping> foundMappings =
        stubMappings.getAll().stream()
            .filter(stub -> !servedStubIds.contains(stub.getId()))
            .collect(Collectors.toList());
    return new ListStubMappingsResult(LimitAndOffsetPaginator.none(foundMappings));
  }

  @Override
  public ListStubMappingsResult findAllStubsByMetadata(StringValuePattern pattern) {
    return new ListStubMappingsResult(
        LimitAndOffsetPaginator.none(stubMappings.findByMetadata(pattern)));
  }

  @Override
  public void removeStubsByMetadata(StringValuePattern pattern) {
    List<StubMapping> foundMappings = stubMappings.findByMetadata(pattern);
    for (StubMapping mapping : foundMappings) {
      removeStubMapping(mapping);
    }
  }

  @Override
  public void importStubs(StubImport stubImport) {
    List<StubMapping> mappings = stubImport.getMappings();
    StubImport.Options importOptions =
        getFirstNonNull(stubImport.getImportOptions(), StubImport.Options.DEFAULTS);

    List<StubMapping> mappingsToSave = new ArrayList<>();
    for (int i = mappings.size() - 1; i >= 0; i--) {
      StubMapping mapping = mappings.get(i);
      if (mapping.getId() != null && getStubMapping(mapping.getId()).isPresent()) {
        if (importOptions.getDuplicatePolicy() == StubImport.Options.DuplicatePolicy.OVERWRITE) {
          editStubMapping(mapping, false);
          if (mapping.shouldBePersisted()) mappingsToSave.add(mapping);
        }
      } else {
        addStubMapping(mapping, false);
        if (mapping.shouldBePersisted()) mappingsToSave.add(mapping);
      }
    }

    if (importOptions.getDeleteAllNotInImport()) {
      List<UUID> ids = mappings.stream().map(StubMapping::getId).collect(Collectors.toList());
      for (StubMapping mapping : listAllStubMappings().getMappings()) {
        if (!ids.contains(mapping.getId())) {
          removeStubMapping(mapping, false);
        }
      }
      mappingsSaver.setAll(mappingsToSave);
    } else {
      if (!mappingsToSave.isEmpty()) mappingsSaver.save(mappingsToSave);
    }
  }

  public Set<String> getLoadedExtensionNames() {
    return extensions.getAllExtensionNames();
  }
}
