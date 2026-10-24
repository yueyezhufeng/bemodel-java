INSERT INTO bm_user (username, password_hash, display_name, role) VALUES
('admin',   '$2b$10$p8qSig4jDf3F2RbVnx34Q.sKckZ2X0hn5w8JF4Cl.tWmICPkhMBOq', '平台管理员', 'ADMIN'),
('modeler', '$2b$10$z.cFB9hTxxLFQTk9ZpVTceEuby2rZjqxDHeLfDoVc8LiEJWBLVngu', '建模工程师', 'EDITOR'),
('viewer',  '$2b$10$Oh/TaO3YMIdHjBhoruu89elxoM7wGJ3ffQJNcsKliH0Kslq3xTMgq', '只读访客', 'VIEWER');
INSERT INTO bm_user (username, password_hash, display_name, role)
VALUES ('reviewer', '$2b$10$vA11VJJnEP8hHQ1oZ1g7CO1K8BH7w4dwIrPbyqIREhkosH4a3XEs6', '本体评审员', 'REVIEWER');
