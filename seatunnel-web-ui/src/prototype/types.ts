export type ImplementationStatus =
  | 'REUSE'
  | 'ADAPT'
  | 'INTEGRATE'
  | 'BUILD'
  | 'LIMITED';

export interface PrototypePageMeta {
  id: string;
  firstMenu: string;
  secondMenu: string;
  route: string;
  technicalModules: string[];
  implementationStatus: ImplementationStatus;
  requirementIds: string[];
  source: string;
  description: string;
  prototypeKind:
    | 'forms'
    | 'reports'
    | 'discovery'
    | 'cloud-edge'
    | 'edge-access'
    | 'links'
    | 'topology'
    | 'diagnostics'
    | 'lake-resources'
    | 'lifecycle'
    | 'logical-access'
    | 'reused';
}

export interface RequirementRelation {
  id: string;
  parentId: string;
  title: string;
  technicalModule: string;
  strategy: ImplementationStatus;
}

export interface PrototypeRequest {
  url: string;
  method: string;
  body?: Record<string, any>;
}

export type PrototypeRequestHandler = (
  request: PrototypeRequest,
) => Promise<any | undefined>;

export interface PrototypeRecord {
  id: string;
  name: string;
  type: string;
  status: string;
  owner: string;
  updatedAt: string;
  description: string;
  [key: string]: any;
}
