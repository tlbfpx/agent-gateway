import { createBrowserRouter, Navigate } from 'react-router-dom';
import { AppShell } from './layouts/AppShell';
import { Dashboard } from './pages/Dashboard';
import { ModelsList } from './pages/Models/List';
import { ApiKeysList } from './pages/ApiKeys/List';
import { Webhooks } from './pages/Webhooks';
import { Audit } from './pages/Audit';
import { ConfigHistory } from './pages/ConfigHistory';
import { ConfigReloader } from './pages/ConfigReloader';
import { Cache } from './pages/Cache';
import { Guardrails } from './pages/Guardrails';
import { Rbac } from './pages/Rbac';
import { RolesList } from './pages/Roles/List';
import { UserBindings } from './pages/UserBindings';
import { Discovery } from './pages/Discovery';
import { Chat } from './pages/Chat';
import { Settings } from './pages/Settings';
import { Health } from './pages/Health';
import { Agents } from './pages/Agents';
import { CostCenter } from './pages/CostCenter';
import { Reconcile } from './pages/CostCenter/Reconcile';
import { Budgets } from './pages/Budgets';
import { ApiExplorer } from './pages/ApiExplorer';
import { Policies } from './pages/Policies';
import { RateLimit } from './pages/RateLimit';
import { AlertCenter } from './pages/AlertCenter';
import { Traces } from './pages/Traces';
import { Workflows } from './pages/Workflows';
import { Help } from './pages/Help';
import { Feedback } from './pages/Feedback';
import { AdminUsers } from './pages/AdminUsers';
import { Teams } from './pages/Teams';
import { Prompts } from './pages/Prompts';
import { Datasets } from './pages/Datasets';
import { Mcp } from './pages/Mcp';
import { Login } from './pages/Login';
import { K8sGateways } from './pages/K8sGateways';
import { Plugins } from './pages/Plugins';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'models', element: <ModelsList /> },
      { path: 'api-keys', element: <ApiKeysList /> },
      { path: 'webhooks', element: <Webhooks /> },
      { path: 'audit', element: <Audit /> },
      { path: 'config-history', element: <ConfigHistory /> },
      { path: 'config-reloader', element: <ConfigReloader /> },
      { path: 'cache', element: <Cache /> },
      { path: 'guardrails', element: <Guardrails /> },
      { path: 'rbac', element: <Rbac /> },
      { path: 'roles', element: <RolesList /> },
      { path: 'user-bindings', element: <UserBindings /> },
      { path: 'policies', element: <Policies /> },
      { path: 'ratelimit', element: <RateLimit /> },
      { path: 'traces', element: <Traces /> },
      { path: 'workflows', element: <Workflows /> },
      { path: 'alerts', element: <AlertCenter /> },
      { path: 'help', element: <Help /> },
      { path: 'discovery', element: <Discovery /> },
      { path: 'agents', element: <Agents /> },
      { path: 'chat', element: <Chat /> },
      { path: 'cost', element: <CostCenter /> },
      { path: 'cost/reconcile', element: <Reconcile /> },
      { path: 'budgets', element: <Budgets /> },
      { path: 'api', element: <ApiExplorer /> },
      { path: 'settings', element: <Settings /> },
      { path: 'health', element: <Health /> },
      { path: 'feedback', element: <Feedback /> },
      { path: 'admin-users', element: <AdminUsers /> },
      { path: 'teams', element: <Teams /> },
      { path: 'prompts', element: <Prompts /> },
      { path: 'datasets', element: <Datasets /> },
      { path: 'mcp', element: <Mcp /> },
      { path: 'login', element: <Login /> },
      { path: 'k8s', element: <K8sGateways /> },
      { path: 'plugins', element: <Plugins /> },
      { path: '*', element: <Navigate to="/dashboard" replace /> },
    ],
  },
]);