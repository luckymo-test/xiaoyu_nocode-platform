export default {
  requestLibPath: "import request from '@/request'",
  schemaPath: process.env.OPENAPI_SCHEMA_URL
    || 'http://localhost:8124/api/v3/api-docs',
  serversPath: './src/generated',
  projectName: 'api',
  namespace: 'GeneratedAPI',
  enumStyle: 'string-literal',
  declareType: 'type',
}
