declare namespace API {
  type AiCodeGenResultVO = {
    projectPath?: string;
    htmlCode?: string;
    cssCode?: string;
    jsCode?: string;
    description?: string;
  };

  type App = {
    id?: number;
    appName?: string;
    cover?: string;
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

  type AppDeployRequest = {
    appId?: number;
  };

  type AppFeatureAuditRequest = {
    id?: number;
    approved?: boolean;
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
    description?: string;
    tags?: string;
    visibility?: string;
    generateStatus?: string;
    featuredStatus?: number;
    initPrompt?: string;
    deployKey?: string;
    codeGenType?: string;
    priority?: number;
    userId?: number;
    createTime?: string;
    updateTime?: string;
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

  type BaseResponseAppVO = {
    code?: number;
    data?: AppVO;
    message?: string;
  };

  type BaseResponseBoolean = {
    code?: number;
    data?: boolean;
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

  type chatMultiParams = {
    appId: number;
    message?: string;
  };

  type chatMultiStreamParams = {
    appId: number;
    message?: string;
  };

  type chatParams = {
    appId: number;
    message?: string;
  };

  type chatStreamParams = {
    appId: number;
    message?: string;
  };

  type DeleteBatchRequest = {
    ids?: number[];
  };

  type DeleteRequest = {
    id?: number;
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

  type getUserByIdParams = {
    id: number;
  };

  type getUserVOByIdParams = {
    id: number;
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

  type PageUserVO = {
    records?: UserVO[];
    pageNumber?: number;
    pageSize?: number;
    totalPage?: number;
    totalRow?: number;
    optimizeCountQuery?: boolean;
  };

  type SseEmitter = {
    timeout?: number;
  };

  type stopGenerateParams = {
    appId: number;
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
}
