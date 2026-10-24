declare namespace GeneratedAPI {
  type addParams = {
    workspaceId: number;
  };

  type AiCodeGenResultVO = {
    projectPath?: string;
    htmlCode?: string;
    cssCode?: string;
    jsCode?: string;
    description?: string;
    files?: VueFileResult[];
    promptTokens?: number;
    completionTokens?: number;
    totalTokens?: number;
  };

  type AnalyticsDailyVO = {
    statDate?: string;
    revisionId?: number;
    eventName?: string;
    eventCount?: number;
  };

  type AnalyticsEventRequest = {
    appId?: number;
    revisionId?: number;
    eventName?: string;
    sessionId?: string;
    path?: string;
    referrer?: string;
    deviceType?: string;
    region?: string;
    metadata?: string;
  };

  type App = {
    id?: number;
    appName?: string;
    cover?: string;
    compressPictures?: string;
    description?: string;
    tags?: string;
    visibility?: string;
    generateStatus?: string;
    featuredStatus?: number;
    initPrompt?: string;
    codeGenType?: string;
    deployKey?: string;
    deployedTime?: string;
    priority?: number;
    userId?: number;
    workspaceId?: number;
    currentRevision?: number;
    editTime?: string;
    createTime?: string;
    updateTime?: string;
    isDelete?: number;
  };

  type AppAddRequest = {
    appName?: string;
    cover?: string;
    description?: string;
    tags?: string;
    visibility?: string;
    initPrompt?: string;
    codeGenType?: string;
  };

  type AppArtifact = {
    id?: number;
    revisionId?: number;
    jobId?: number;
    appId?: number;
    workspaceId?: number;
    artifactType?: string;
    scope?: string;
    ownerId?: number;
    visibility?: string;
    uri?: string;
    sha256?: string;
    sizeBytes?: number;
    mimeType?: string;
    metadata?: string;
    createTime?: string;
  };

  type AppDeployRequest = {
    appId?: number;
  };

  type AppFeatureAuditRequest = {
    id?: number;
    approved?: boolean;
  };

  type applyParams = {
    appId: number;
  };

  type AppPublicationVO = {
    id?: number;
    appId?: number;
    revisionId?: number;
    workspaceId?: number;
    publisherId?: number;
    status?: string;
    title?: string;
    description?: string;
    license?: string;
    forkAllowed?: boolean;
    reviewNote?: string;
    publishedAt?: string;
    createTime?: string;
  };

  type AppQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    id?: number;
    appName?: string;
    cover?: string;
    description?: string;
    tags?: string;
    visibility?: string;
    featuredStatus?: number;
    searchText?: string;
    initPrompt?: string;
    codeGenType?: string;
    deployKey?: string;
    priority?: number;
    userId?: number;
  };

  type AppRevisionDiffVO = {
    fromRevisionId?: number;
    toRevisionId?: number;
    files?: FileChange[];
  };

  type AppRevisionVO = {
    id?: number;
    appId?: number;
    workspaceId?: number;
    jobId?: number;
    revisionNo?: number;
    baseRevisionId?: number;
    status?: string;
    codeGenType?: string;
    stackProfileVersion?: string;
    templateBundleId?: string;
    templateVersion?: string;
    templateHash?: string;
    skillIds?: string[];
    sourceUri?: string;
    validationStatus?: string;
    createdBy?: number;
    publishedAt?: string;
    createTime?: string;
    artifacts?: AppArtifact[];
  };

  type AppUpdateRequest = {
    id?: number;
    appName?: string;
    cover?: string;
    description?: string;
    tags?: string;
    visibility?: string;
    priority?: number;
  };

  type AppVO = {
    id?: number;
    appName?: string;
    cover?: string;
    compressPictures?: string;
    description?: string;
    tags?: string;
    visibility?: string;
    generateStatus?: string;
    featuredStatus?: number;
    initPrompt?: string;
    deployKey?: string;
    deployUrl?: string;
    codeGenType?: string;
    priority?: number;
    userId?: number;
    workspaceId?: number;
    currentRevision?: number;
    createTime?: string;
    updateTime?: string;
  };

  type AssetVO = {
    id?: number;
    workspaceId?: number;
    ownerId?: number;
    assetType?: string;
    sourceType?: string;
    displayName?: string;
    originalUri?: string;
    sha256?: string;
    mimeType?: string;
    extension?: string;
    sizeBytes?: number;
    width?: number;
    height?: number;
    durationMs?: number;
    license?: string;
    visibility?: string;
    moderationStatus?: string;
    createTime?: string;
    variants?: LibraryAssetVariant[];
  };

  type BaseResponseAiCodeGenResultVO = {
    code?: number;
    data?: AiCodeGenResultVO;
    message?: string;
  };

  type BaseResponseApp = {
    code?: number;
    data?: App;
    message?: string;
  };

  type BaseResponseAppPublicationVO = {
    code?: number;
    data?: AppPublicationVO;
    message?: string;
  };

  type BaseResponseAppRevisionDiffVO = {
    code?: number;
    data?: AppRevisionDiffVO;
    message?: string;
  };

  type BaseResponseAppRevisionVO = {
    code?: number;
    data?: AppRevisionVO;
    message?: string;
  };

  type BaseResponseAppVO = {
    code?: number;
    data?: AppVO;
    message?: string;
  };

  type BaseResponseAssetVO = {
    code?: number;
    data?: AssetVO;
    message?: string;
  };

  type BaseResponseBoolean = {
    code?: number;
    data?: boolean;
    message?: string;
  };

  type BaseResponseEditItem = {
    code?: number;
    data?: EditItem;
    message?: string;
  };

  type BaseResponseGenerationJobVO = {
    code?: number;
    data?: GenerationJobVO;
    message?: string;
  };

  type BaseResponseInteger = {
    code?: number;
    data?: number;
    message?: string;
  };

  type BaseResponseListAnalyticsDailyVO = {
    code?: number;
    data?: AnalyticsDailyVO[];
    message?: string;
  };

  type BaseResponseListAppPublicationVO = {
    code?: number;
    data?: AppPublicationVO[];
    message?: string;
  };

  type BaseResponseListAppRevisionVO = {
    code?: number;
    data?: AppRevisionVO[];
    message?: string;
  };

  type BaseResponseListAssetVO = {
    code?: number;
    data?: AssetVO[];
    message?: string;
  };

  type BaseResponseListChatHistory = {
    code?: number;
    data?: ChatHistory[];
    message?: string;
  };

  type BaseResponseListEditItem = {
    code?: number;
    data?: EditItem[];
    message?: string;
  };

  type BaseResponseListSkillDefinitionVO = {
    code?: number;
    data?: SkillDefinitionVO[];
    message?: string;
  };

  type BaseResponseListStackProfileVO = {
    code?: number;
    data?: StackProfileVO[];
    message?: string;
  };

  type BaseResponseListTemplateBundleVO = {
    code?: number;
    data?: TemplateBundleVO[];
    message?: string;
  };

  type BaseResponseListWorkspaceMemberVO = {
    code?: number;
    data?: WorkspaceMemberVO[];
    message?: string;
  };

  type BaseResponseLoginUserVO = {
    code?: number;
    data?: LoginUserVO;
    message?: string;
  };

  type BaseResponseLong = {
    code?: number;
    data?: number;
    message?: string;
  };

  type BaseResponseMapStringBoolean = {
    code?: number;
    data?: Record<string, any>;
    message?: string;
  };

  type BaseResponseMapStringObject = {
    code?: number;
    data?: Record<string, any>;
    message?: string;
  };

  type BaseResponsePageApp = {
    code?: number;
    data?: PageApp;
    message?: string;
  };

  type BaseResponsePageAppVO = {
    code?: number;
    data?: PageAppVO;
    message?: string;
  };

  type BaseResponsePageChatAppVO = {
    code?: number;
    data?: PageChatAppVO;
    message?: string;
  };

  type BaseResponsePageChatHistory = {
    code?: number;
    data?: PageChatHistory;
    message?: string;
  };

  type BaseResponsePageUserVO = {
    code?: number;
    data?: PageUserVO;
    message?: string;
  };

  type BaseResponseString = {
    code?: number;
    data?: string;
    message?: string;
  };

  type BaseResponseUser = {
    code?: number;
    data?: User;
    message?: string;
  };

  type BaseResponseUserVO = {
    code?: number;
    data?: UserVO;
    message?: string;
  };

  type BaseResponseWorkspaceMemberVO = {
    code?: number;
    data?: WorkspaceMemberVO;
    message?: string;
  };

  type cancelParams = {
    jobId: number;
  };

  type ChatAppVO = {
    appId?: number;
    appName?: string;
    userId?: number;
    messageCount?: number;
    lastCreateTime?: string;
  };

  type ChatHistory = {
    id?: number;
    message?: string;
    messageType?: string;
    appId?: number;
    userId?: number;
    createTime?: string;
    updateTime?: string;
    isDelete?: number;
    parentId?: number;
  };

  type ChatHistoryQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    appId?: number;
    userId?: number;
    messageType?: string;
    beforeCreateTime?: string;
    beforeId?: number;
  };

  type chatMultiParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
  };

  type chatMultiStreamParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
    idempotencyKey?: string;
    after?: number;
  };

  type chatParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
  };

  type chatStreamParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
    idempotencyKey?: string;
    after?: number;
  };

  type chatVueParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
  };

  type chatVueStreamParams = {
    appId: number;
    message?: string;
    reasoningLevel?: string;
    idempotencyKey?: string;
    after?: number;
  };

  type clearParams = {
    appId: number;
  };

  type compareParams = {
    appId: number;
    from: number;
    to: number;
  };

  type create1Params = {
    appId: number;
  };

  type dailyParams = {
    appId: number;
    from?: string;
    to?: string;
  };

  type DeleteBatchRequest = {
    ids?: number[];
  };

  type DeleteRequest = {
    id?: number;
  };

  type deployAppStreamParams = {
    appId: number;
  };

  type downloadCodeParams = {
    id: number;
  };

  type EditItem = {
    id?: string;
    kind?: string;
    selector?: string;
    content?: string;
  };

  type eventsParams = {
    jobId: number;
    after?: number;
  };

  type FileChange = {
    path?: string;
    changeType?: string;
    fromSha256?: string;
    toSha256?: string;
  };

  type GenerationJobCreateRequest = {
    message?: string;
    reasoningLevel?: string;
    codeGenType?: string;
    stackProfileId?: string;
    templateHint?: string;
    templateBundleId?: string;
    templateVersion?: string;
    templateHash?: string;
    skills?: string[];
    requestType?: string;
    visibility?: string;
  };

  type GenerationJobVO = {
    jobId?: number;
    workspaceId?: number;
    appId?: number;
    requestedBy?: number;
    requestType?: string;
    status?: string;
    currentStage?: string;
    codeGenType?: string;
    stackProfileId?: string;
    stackProfileVersion?: string;
    templateBundleId?: string;
    templateVersion?: string;
    templateHash?: string;
    skillIds?: string[];
    visibility?: string;
    attempt?: number;
    maxAttempts?: number;
    errorType?: string;
    errorMessage?: string;
    eventsUrl?: string;
    createTime?: string;
    updateTime?: string;
  };

  type get1Params = {
    assetId: number;
  };

  type get2Params = {
    appId: number;
    revisionId: number;
  };

  type getAppByIdParams = {
    id: number;
  };

  type getAppCodeParams = {
    id: number;
  };

  type getAppVOByIdParams = {
    id: number;
  };

  type getParams = {
    jobId: number;
  };

  type getPublishedParams = {
    publicationId: number;
  };

  type getUserByIdParams = {
    id: number;
  };

  type getUserVOByIdParams = {
    id: number;
  };

  type getVuePreviewParams = {
    id: number;
  };

  type LibraryAssetVariant = {
    id?: number;
    assetId?: number;
    variantType?: string;
    uri?: string;
    format?: string;
    width?: number;
    height?: number;
    sizeBytes?: number;
    createTime?: string;
  };

  type list5Params = {
    workspaceId?: number;
    assetType?: string;
  };

  type list6Params = {
    appId: number;
  };

  type list7Params = {
    appId: number;
  };

  type listParams = {
    workspaceId: number;
  };

  type LoginUserVO = {
    id?: number;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
    updateTime?: string;
  };

  type modifyStreamParams = {
    appId: number;
    elementContext?: string;
    instruction: string;
    reasoningLevel?: string;
  };

  type PageApp = {
    records?: App[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PageAppVO = {
    records?: AppVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PageChatAppVO = {
    records?: ChatAppVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PageChatHistory = {
    records?: ChatHistory[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PageUserVO = {
    records?: UserVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type PublicationCreateRequest = {
    appId?: number;
    revisionId?: number;
    title?: string;
    description?: string;
    license?: string;
    forkAllowed?: boolean;
  };

  type PublicationReviewRequest = {
    approved?: boolean;
    reviewNote?: string;
  };

  type publishParams = {
    appId: number;
    revisionId: number;
  };

  type removeParams = {
    workspaceId: number;
    userId: number;
  };

  type renderFixStatusParams = {
    appId: number;
  };

  type retryParams = {
    jobId: number;
  };

  type reviewParams = {
    publicationId: number;
  };

  type rollbackParams = {
    appId: number;
    revisionId: number;
  };

  type SkillDefinitionVO = {
    id?: string;
    version?: string;
    displayName?: string;
    compatibleProfiles?: string[];
    allowedTools?: string[];
    permissions?: string[];
    promptFragment?: string;
    validationRules?: string[];
    conflictsWith?: string[];
  };

  type SseEmitter = {
    timeout?: number;
  };

  type StackProfileVO = {
    id?: string;
    version?: string;
    displayName?: string;
    compatibilityType?: string;
    capabilities?: string[];
    available?: boolean;
    unavailableReason?: string;
    installCommands?: string[][];
    buildCommands?: string[][];
    outputDirectory?: string;
  };

  type stopGenerateParams = {
    appId: number;
  };

  type StreamingResponseBody = Record<string, any>;

  type takedownParams = {
    publicationId: number;
    note?: string;
  };

  type TemplateBundleVO = {
    id?: string;
    version?: string;
    displayName?: string;
    compatibleProfiles?: string[];
    sectionIds?: string[];
    designTokens?: Record<string, any>;
    promptFragment?: string;
    effectiveHash?: string;
  };

  type undoModifyParams = {
    appId: number;
  };

  type undoParams = {
    appId: number;
    id: string;
  };

  type uploadParams = {
    workspaceId?: number;
    assetType?: string;
    visibility?: string;
    license?: string;
  };

  type User = {
    id?: number;
    userAccount?: string;
    userPassword?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    editTime?: string;
    createTime?: string;
    updateTime?: string;
    isDelete?: number;
    vipExpireTime?: string;
    vipCode?: string;
    vipNumber?: number;
  };

  type UserAddRequest = {
    userName?: string;
    userAccount?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserLoginRequest = {
    userAccount?: string;
    userPassword?: string;
  };

  type UserQueryRequest = {
    pageNum?: number;
    pageSize?: number;
    sortField?: string;
    sortOrder?: string;
    id?: number;
    userName?: string;
    userAccount?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserRegisterRequest = {
    userAccount?: string;
    userPassword?: string;
    checkPassword?: string;
  };

  type UserUpdateRequest = {
    id?: number;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
  };

  type UserVO = {
    id?: number;
    userAccount?: string;
    userName?: string;
    userAvatar?: string;
    userProfile?: string;
    userRole?: string;
    createTime?: string;
  };

  type VueFileResult = {
    path?: string;
    content?: string;
  };

  type WorkspaceMemberAddRequest = {
    userAccount?: string;
    role?: string;
  };

  type WorkspaceMemberVO = {
    id?: number;
    workspaceId?: number;
    userId?: number;
    userAccount?: string;
    userName?: string;
    role?: string;
    createTime?: string;
  };
}
